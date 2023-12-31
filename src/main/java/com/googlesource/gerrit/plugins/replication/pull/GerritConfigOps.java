// Copyright (C) 2021 The Android Open Source Project
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

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.config.SitePaths;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.URIish;

@Singleton
public class GerritConfigOps {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final SitePaths sitePath;
  private final Config gerritConfig;

  @Inject
  public GerritConfigOps(@GerritServerConfig Config cfg, SitePaths sitePath) {
    this.sitePath = sitePath;
    this.gerritConfig = cfg;
  }

  public Optional<URIish> getGitRepositoryURI(String projectName) {
    Path basePath = sitePath.resolve(gerritConfig.getString("gerrit", null, "basePath"));
    URIish uri;

    try {
      uri = new URIish("file://" + basePath + "/" + projectName);
      return Optional.of(uri);
    } catch (URISyntaxException e) {
      logger.atSevere().withCause(e).log("Unsupported URI for project %s", projectName);
    }

    return Optional.empty();
  }
}
