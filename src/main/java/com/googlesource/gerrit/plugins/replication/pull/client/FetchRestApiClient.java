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

package com.googlesource.gerrit.plugins.replication.pull.client;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.Url;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.googlesource.gerrit.plugins.replication.CredentialsFactory;
import com.googlesource.gerrit.plugins.replication.ReplicationConfig;
import com.googlesource.gerrit.plugins.replication.pull.Source;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;

public class FetchRestApiClient implements ResponseHandler<HttpResult> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  static String GERRIT_ADMIN_PROTOCOL_PREFIX = "gerrit+";

  public interface Factory {
    FetchRestApiClient create(Source source);
  }

  private final CredentialsFactory credentials;
  private final SourceHttpClient.Factory httpClientFactory;
  private final Source source;
  private final String instanceLabel;

  @Inject
  FetchRestApiClient(
      CredentialsFactory credentials,
      SourceHttpClient.Factory httpClientFactory,
      ReplicationConfig replicationConfig,
      @Assisted Source source) {
    this.credentials = credentials;
    this.httpClientFactory = httpClientFactory;
    this.source = source;
    this.instanceLabel =
        Strings.nullToEmpty(
                replicationConfig.getConfig().getString("replication", null, "instanceLabel"))
            .trim();
    requireNonNull(
        Strings.emptyToNull(instanceLabel), "replication.instanceLabel cannot be null or empty");
  }

  public HttpResult callFetch(Project.NameKey project, String refName, URIish targetUri)
      throws ClientProtocolException, IOException {
    String url =
        String.format(
            "%s/a/projects/%s/pull-replication~fetch",
            targetUri.toString(), Url.encode(project.get()));

    HttpPost post = new HttpPost(url);
    post.setEntity(
        new StringEntity(
            String.format("{\"label\":\"%s\", \"ref_name\": \"%s\"}", instanceLabel, refName),
            StandardCharsets.UTF_8));
    post.addHeader(new BasicHeader("Content-Type", "application/json"));
    return httpClientFactory.create(source).execute(post, this, getContext(targetUri));
  }

  @Override
  public HttpResult handleResponse(HttpResponse response) {
    Optional<String> responseBody = Optional.empty();

    try {
      responseBody = Optional.ofNullable(EntityUtils.toString(response.getEntity()));
    } catch (ParseException | IOException e) {
      logger.atSevere().withCause(e).log("Unable get response body from %s", response.toString());
    }
    return new HttpResult(response.getStatusLine().getStatusCode(), responseBody);
  }

  private HttpClientContext getContext(URIish targetUri) {
    HttpClientContext ctx = HttpClientContext.create();
    ctx.setCredentialsProvider(adapt(credentials.create(source.getRemoteConfigName()), targetUri));
    return ctx;
  }

  private CredentialsProvider adapt(org.eclipse.jgit.transport.CredentialsProvider cp, URIish uri) {
    CredentialItem.Username user = new CredentialItem.Username();
    CredentialItem.Password pass = new CredentialItem.Password();
    if (cp.supports(user, pass) && cp.get(uri, user, pass)) {
      CredentialsProvider adapted = new BasicCredentialsProvider();
      adapted.setCredentials(
          AuthScope.ANY,
          new UsernamePasswordCredentials(user.getValue(), new String(pass.getValue())));
      return adapted;
    }
    return null;
  }
}
