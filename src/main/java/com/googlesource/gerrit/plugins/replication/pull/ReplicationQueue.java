// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication.pull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.Queues;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.Project.NameKey;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.metrics.Timer1.Context;
import com.google.gerrit.server.events.EventDispatcher;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.ObservableQueue;
import com.googlesource.gerrit.plugins.replication.pull.FetchResultProcessing.GitUpdateProcessing;
import com.googlesource.gerrit.plugins.replication.pull.api.data.RevisionData;
import com.googlesource.gerrit.plugins.replication.pull.api.exception.MissingParentObjectException;
import com.googlesource.gerrit.plugins.replication.pull.client.FetchApiClient;
import com.googlesource.gerrit.plugins.replication.pull.client.HttpResult;
import com.googlesource.gerrit.plugins.replication.pull.filter.ExcludedRefsFilter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.http.client.ClientProtocolException;
import org.eclipse.jgit.errors.InvalidObjectIdException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.URIish;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplicationQueue
    implements ObservableQueue,
        LifecycleListener,
        GitReferenceUpdatedListener,
        ProjectDeletedListener,
        HeadUpdatedListener {

  static final String PULL_REPLICATION_LOG_NAME = "pull_replication_log";
  static final Logger repLog = LoggerFactory.getLogger(PULL_REPLICATION_LOG_NAME);

  private static final Integer DEFAULT_FETCH_CALLS_TIMEOUT = 0;
  private final ReplicationStateListener stateLog;

  private final WorkQueue workQueue;
  private final DynamicItem<EventDispatcher> dispatcher;
  private final Provider<SourcesCollection> sources; // For Guice circular dependency
  private volatile boolean running;
  private volatile boolean replaying;
  private final Queue<ReferenceUpdatedEvent> beforeStartupEventsQueue;
  private FetchApiClient.Factory fetchClientFactory;
  private Integer fetchCallsTimeout;
  private ExcludedRefsFilter refsFilter;
  private RevisionReader revisionReader;
  private final ApplyObjectMetrics applyObjectMetrics;
  private final FetchReplicationMetrics fetchMetrics;

  @Inject
  ReplicationQueue(
      WorkQueue wq,
      Provider<SourcesCollection> rd,
      DynamicItem<EventDispatcher> dis,
      ReplicationStateListeners sl,
      FetchApiClient.Factory fetchClientFactory,
      ExcludedRefsFilter refsFilter,
      RevisionReader revReader,
      ApplyObjectMetrics applyObjectMetrics,
      FetchReplicationMetrics fetchMetrics) {
    workQueue = wq;
    dispatcher = dis;
    sources = rd;
    stateLog = sl;
    beforeStartupEventsQueue = Queues.newConcurrentLinkedQueue();
    this.fetchClientFactory = fetchClientFactory;
    this.refsFilter = refsFilter;
    this.revisionReader = revReader;
    this.applyObjectMetrics = applyObjectMetrics;
    this.fetchMetrics = fetchMetrics;
  }

  @Override
  public void start() {
    if (!running) {
      sources.get().startup(workQueue);
      fetchCallsTimeout =
          2
              * sources.get().getAll().stream()
                  .mapToInt(Source::getConnectionTimeout)
                  .max()
                  .orElse(DEFAULT_FETCH_CALLS_TIMEOUT);

      running = true;
      fireBeforeStartupEvents();
    }
  }

  @Override
  public void stop() {
    running = false;
    int discarded = sources.get().shutdown();
    if (discarded > 0) {
      repLog.warn("Canceled {} replication events during shutdown", discarded);
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public boolean isReplaying() {
    return replaying;
  }

  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    if (isRefToBeReplicated(event.getRefName())) {
      repLog.info(
          "Ref event received: {} on project {}:{} - {} => {}",
          refUpdateType(event),
          event.getProjectName(),
          event.getRefName(),
          event.getOldObjectId(),
          event.getNewObjectId());
      fire(
          event.getProjectName(),
          ObjectId.fromString(event.getNewObjectId()),
          event.getRefName(),
          event.isDelete());
    }
  }

  @Override
  public void onProjectDeleted(ProjectDeletedListener.Event event) {
    Project.NameKey project = Project.nameKey(event.getProjectName());
    sources.get().getAll().stream()
        .filter((Source s) -> s.wouldDeleteProject(project))
        .forEach(
            source ->
                source.getApis().forEach(apiUrl -> source.scheduleDeleteProject(apiUrl, project)));
  }

  private static String refUpdateType(GitReferenceUpdatedListener.Event event) {
    String forcedPrefix = event.isNonFastForward() ? "FORCED " : " ";
    if (event.isCreate()) {
      return forcedPrefix + "CREATE";
    } else if (event.isDelete()) {
      return forcedPrefix + "DELETE";
    } else {
      return forcedPrefix + "UPDATE";
    }
  }

  private Boolean isRefToBeReplicated(String refName) {
    return !refsFilter.match(refName);
  }

  private void fire(String projectName, ObjectId objectId, String refName, boolean isDelete) {
    ReplicationState state = new ReplicationState(new GitUpdateProcessing(dispatcher.get()));
    fire(Project.nameKey(projectName), objectId, refName, isDelete, state);
    state.markAllFetchTasksScheduled();
  }

  private void fire(
      Project.NameKey project,
      ObjectId objectId,
      String refName,
      boolean isDelete,
      ReplicationState state) {
    if (!running) {
      stateLog.warn(
          "Replication plugin did not finish startup before event, event replication is postponed",
          state);
      beforeStartupEventsQueue.add(
          ReferenceUpdatedEvent.create(project.get(), refName, objectId, isDelete));
      return;
    }
    ForkJoinPool fetchCallsPool = null;
    try {
      fetchCallsPool = new ForkJoinPool(sources.get().getAll().size());

      final Consumer<Source> callFunction =
          callFunction(project, objectId, refName, isDelete, state);
      fetchCallsPool
          .submit(() -> sources.get().getAll().parallelStream().forEach(callFunction))
          .get(fetchCallsTimeout, TimeUnit.MILLISECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      stateLog.error(
          String.format(
              "Exception during the pull replication fetch rest api call.  Message:%s",
              e.getMessage()),
          e,
          state);
    } finally {
      if (fetchCallsPool != null) {
        fetchCallsPool.shutdown();
      }
    }
  }

  private Consumer<Source> callFunction(
      NameKey project,
      ObjectId objectId,
      String refName,
      boolean isDelete,
      ReplicationState state) {
    CallFunction call = getCallFunction(project, objectId, refName, isDelete, state);

    return (source) -> {
      boolean callSuccessful;
      try {
        callSuccessful = call.call(source);
      } catch (Exception e) {
        repLog.warn(
            String.format(
                "Failed to apply object %s on project %s:%s, falling back to git fetch",
                objectId.name(), project, refName),
            e);
        callSuccessful = false;
      }

      if (!callSuccessful) {
        callFetch(source, project, refName, state);
      }
    };
  }

  private CallFunction getCallFunction(
      NameKey project,
      ObjectId objectId,
      String refName,
      boolean isDelete,
      ReplicationState state) {
    if (isDelete) {
      return ((source) -> callSendObject(source, project, refName, isDelete, null, state));
    }

    try {
      Optional<RevisionData> revisionData = revisionReader.read(project, objectId, refName);
      repLog.info(
          "RevisionData is {} for {}:{}",
          revisionData.map(RevisionData::toString).orElse("ABSENT"),
          project,
          refName);

      if (revisionData.isPresent()) {
        return ((source) ->
            callSendObject(source, project, refName, isDelete, revisionData.get(), state));
      }
    } catch (InvalidObjectIdException | IOException e) {
      stateLog.error(
          String.format(
              "Exception during reading ref: %s, project:%s, message: %s",
              refName, project.get(), e.getMessage()),
          e,
          state);
    }

    return (source) -> callFetch(source, project, refName, state);
  }

  private boolean callSendObject(
      Source source,
      Project.NameKey project,
      String refName,
      boolean isDelete,
      RevisionData revision,
      ReplicationState state)
      throws MissingParentObjectException {
    boolean resultIsSuccessful = true;
    if (source.wouldFetchProject(project) && source.wouldFetchRef(refName)) {
      for (String apiUrl : source.getApis()) {
        try {
          URIish uri = new URIish(apiUrl);
          FetchApiClient fetchClient = fetchClientFactory.create(source);
          repLog.info(
              "Pull replication REST API apply object to {} for {}:{} - {}",
              apiUrl,
              project,
              refName,
              revision);
          Context<String> apiTimer = applyObjectMetrics.startEnd2End(source.getRemoteConfigName());
          HttpResult result = fetchClient.callSendObject(project, refName, isDelete, revision, uri);
          repLog.info(
              "Pull replication REST API apply object to {} COMPLETED for {}:{} - {}, HTTP Result:"
                  + " {} - time:{} ms",
              apiUrl,
              project,
              refName,
              revision,
              result,
              apiTimer.stop() / 1000000.0);

          if (isProjectMissing(result, project) && source.isCreateMissingRepositories()) {
            result = initProject(project, uri, fetchClient, result);
            repLog.info("Missing project {} created, HTTP Result:{}", project, result);
          }

          if (!result.isSuccessful()) {
            if (result.isParentObjectMissing()) {
              throw new MissingParentObjectException(
                  project, refName, source.getRemoteConfigName());
            }
          }

          resultIsSuccessful &= result.isSuccessful();
        } catch (URISyntaxException e) {
          repLog.warn(
              "Pull replication REST API apply object to {} *FAILED* for {}:{} - {}",
              apiUrl,
              project,
              refName,
              revision,
              e);
          stateLog.error(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
          resultIsSuccessful = false;
        } catch (IOException e) {
          repLog.warn(
              "Pull replication REST API apply object to {} *FAILED* for {}:{} - {}",
              apiUrl,
              project,
              refName,
              revision,
              e);
          stateLog.error(
              String.format(
                  "Exception during the pull replication fetch rest api call. Endpoint url:%s,"
                      + " message:%s",
                  apiUrl, e.getMessage()),
              e,
              state);
          resultIsSuccessful = false;
        }
      }
    }

    return resultIsSuccessful;
  }

  private boolean callFetch(
      Source source, Project.NameKey project, String refName, ReplicationState state) {
    boolean resultIsSuccessful = true;
    if (source.wouldFetchProject(project) && source.wouldFetchRef(refName)) {
      for (String apiUrl : source.getApis()) {
        try {
          URIish uri = new URIish(apiUrl);
          FetchApiClient fetchClient = fetchClientFactory.create(source);
          repLog.info("Pull replication REST API fetch to {} for {}:{}", apiUrl, project, refName);
          Context<String> timer = fetchMetrics.startEnd2End(source.getRemoteConfigName());
          HttpResult result = fetchClient.callFetch(project, refName, uri, timer.getStartTime());
          long elapsedMs = TimeUnit.NANOSECONDS.toMillis(timer.stop());
          repLog.info(
              "Pull replication REST API fetch to {} COMPLETED for {}:{}, HTTP Result:"
                  + " {} - time:{} ms",
              apiUrl,
              project,
              refName,
              result,
              elapsedMs);
          if (isProjectMissing(result, project) && source.isCreateMissingRepositories()) {
            result = initProject(project, uri, fetchClient, result);
          }
          if (!result.isSuccessful()) {
            stateLog.warn(
                String.format(
                    "Pull replication rest api fetch call failed. Endpoint url: %s, reason:%s",
                    apiUrl, result.getMessage().orElse("unknown")),
                state);
          }

          resultIsSuccessful &= result.isSuccessful();
        } catch (URISyntaxException e) {
          stateLog.error(String.format("Cannot parse pull replication api url:%s", apiUrl), state);
          resultIsSuccessful = false;
        } catch (Exception e) {
          stateLog.error(
              String.format(
                  "Exception during the pull replication fetch rest api call. Endpoint url:%s,"
                      + " message:%s",
                  apiUrl, e.getMessage()),
              e,
              state);
          resultIsSuccessful = false;
        }
      }
    }

    return resultIsSuccessful;
  }

  public boolean retry(int attempt, int maxRetries) {
    return maxRetries == 0 || attempt < maxRetries;
  }

  private Boolean isProjectMissing(HttpResult result, Project.NameKey project) {
    return !result.isSuccessful() && result.isProjectMissing(project);
  }

  private HttpResult initProject(
      Project.NameKey project, URIish uri, FetchApiClient fetchClient, HttpResult result)
      throws IOException, ClientProtocolException {
    HttpResult initProjectResult = fetchClient.initProject(project, uri);
    if (initProjectResult.isSuccessful()) {
      result = fetchClient.callFetch(project, FetchOne.ALL_REFS, uri);
    } else {
      String errorMessage = initProjectResult.getMessage().map(e -> " - Error: " + e).orElse("");
      repLog.error("Cannot create project " + project + errorMessage);
    }
    return result;
  }

  private void fireBeforeStartupEvents() {
    Set<String> eventsReplayed = new HashSet<>();
    for (ReferenceUpdatedEvent event : beforeStartupEventsQueue) {
      String eventKey = String.format("%s:%s", event.projectName(), event.refName());
      if (!eventsReplayed.contains(eventKey)) {
        repLog.info("Firing pending task {}", event);
        fire(event.projectName(), event.objectId(), event.refName(), event.isDelete());
        eventsReplayed.add(eventKey);
      }
    }
  }

  @Override
  public void onHeadUpdated(HeadUpdatedListener.Event event) {
    Project.NameKey p = Project.nameKey(event.getProjectName());
    sources.get().getAll().stream()
        .filter(s -> s.wouldFetchProject(p))
        .forEach(
            s ->
                s.getApis()
                    .forEach(apiUrl -> s.scheduleUpdateHead(apiUrl, p, event.getNewHeadName())));
  }

  @AutoValue
  abstract static class ReferenceUpdatedEvent {

    static ReferenceUpdatedEvent create(
        String projectName, String refName, ObjectId objectId, boolean isDelete) {
      return new AutoValue_ReplicationQueue_ReferenceUpdatedEvent(
          projectName, refName, objectId, isDelete);
    }

    public abstract String projectName();

    public abstract String refName();

    public abstract ObjectId objectId();

    public abstract boolean isDelete();
  }

  @FunctionalInterface
  private interface CallFunction {
    boolean call(Source source) throws MissingParentObjectException;
  }
}
