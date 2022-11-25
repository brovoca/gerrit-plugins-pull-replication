// Copyright (C) 2022 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.replication.pull.api;

import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.httpd.WebSession;
import com.google.gerrit.server.AccessPath;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.replication.pull.auth.PullReplicationInternalUser;
import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class BearerAuthenticationFilterTest {

  @Mock private DynamicItem<WebSession> session;
  @Mock private WebSession webSession;
  @Mock private Provider<ThreadLocalRequestContext> threadLocalRequestContextProvider;
  @Mock private PullReplicationInternalUser pluginUser;
  @Mock private ThreadLocalRequestContext threadLocalRequestContext;
  @Mock private HttpServletRequest httpServletRequest;
  @Mock private HttpServletResponse httpServletResponse;
  @Mock private FilterChain filterChain;
  private final String pluginName = "pull-replication";

  private void authenticateWithURI(String uri) throws ServletException, IOException {
    final String bearerToken = "some-bearer-token";
    when(httpServletRequest.getRequestURI()).thenReturn(uri);
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", bearerToken));
    when(threadLocalRequestContextProvider.get()).thenReturn(threadLocalRequestContext);
    when(session.get()).thenReturn(webSession);
    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session, pluginName, pluginUser, threadLocalRequestContextProvider, bearerToken);
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(threadLocalRequestContextProvider).get();
    verify(session).get();
    verify(webSession).setAccessPathOk(AccessPath.REST_API, true);
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenFetch() throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~fetch");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenApplyObject()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~apply-object");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenApplyObjects()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~apply-objects");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenDeleteProject()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication~delete-project");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenUpdateHead()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/projects/my-project/HEAD");
  }

  @Test
  public void shouldAuthenticateWithBearerTokenWhenInitProject()
      throws ServletException, IOException {
    authenticateWithURI("any-prefix/pull-replication/init-project/my-project.git");
  }

  @Test
  public void shouldBe401WhenBearerTokenDoesNotMatch() throws ServletException, IOException {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization"))
        .thenReturn(String.format("Bearer %s", "some-different-bearer-token"));

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(httpServletResponse).sendError(SC_UNAUTHORIZED);
  }

  @Test
  public void shouldBe401WhenBearerTokenCannotBeExtracted() throws ServletException, IOException {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");
    when(httpServletRequest.getHeader("Authorization")).thenReturn("bearer token");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(httpServletRequest).getHeader("Authorization");
    verify(httpServletResponse).sendError(SC_UNAUTHORIZED);
  }

  @Test
  public void shouldBe401WhenNoAuthorizationHeaderInRequest() throws ServletException, IOException {
    when(httpServletRequest.getRequestURI()).thenReturn("any-prefix/pull-replication~fetch");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(httpServletResponse).sendError(SC_UNAUTHORIZED);
  }

  @Test
  public void shouldGoNextInChainWhenUriDoesNotMatch() throws ServletException, IOException {
    when(httpServletRequest.getRequestURI()).thenReturn("any-url");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }

  @Test
  public void shouldGoNextInChainWhenBasicAuthorizationIsRequired()
      throws ServletException, IOException {
    when(httpServletRequest.getRequestURI())
        .thenReturn("/a/projects/my-project/pull-replication~fetch");

    final BearerAuthenticationFilter filter =
        new BearerAuthenticationFilter(
            session,
            pluginName,
            pluginUser,
            threadLocalRequestContextProvider,
            "some-bearer-token");
    filter.doFilter(httpServletRequest, httpServletResponse, filterChain);

    verify(httpServletRequest).getRequestURI();
    verify(filterChain).doFilter(httpServletRequest, httpServletResponse);
  }
}
