/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.repositorydriver;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import io.undertow.util.Headers;
import org.jboss.pnc.api.constants.MDCHeaderKeys;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.repositorydriver.dto.CreateRequest;
import org.jboss.pnc.repositorydriver.dto.CreateResponse;
import org.jboss.pnc.repositorydriver.invokerserver.CallbackHandler;
import org.jboss.pnc.repositorydriver.invokerserver.HttpServer;
import org.jboss.pnc.repositorydriver.invokerserver.ServletInstanceFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.restassured.RestAssured.given;
import static org.jboss.pnc.repositorydriver.dto.EnvironmentCreationCompleted.Status.FAILED;
import static org.jboss.pnc.repositorydriver.dto.EnvironmentCreationCompleted.Status.SUCCESS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
@QuarkusTest
@TestSecurity(authorizationEnabled = false)
public class DriverTest {

    private static final String BIND_HOST = "127.0.0.1";

    private static final Logger logger = LoggerFactory.getLogger(DriverTest.class);

    private static ObjectMapper mapper = new ObjectMapper();

    private static HttpServer callbackServer;

    private static final BlockingQueue<Request> callbackRequests = new ArrayBlockingQueue<>(100);
    private static final BlockingQueue<Request> pingRequests = new ArrayBlockingQueue<>(100);

    @BeforeAll
    public static void beforeClass() throws Exception {
        // uncomment to log all requests
        // RestAssured.filters(new RequestLoggingFilter(), new ResponseLoggingFilter());
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        callbackServer = new HttpServer();

        callbackServer.addServlet(
                CallbackHandler.class,
                new ServletInstanceFactory(new CallbackHandler(callbackRequests::add)));
        callbackServer.addServlet(PingHandler.class, new ServletInstanceFactory(new PingHandler(pingRequests::add)));
        callbackServer.start(8082, BIND_HOST);
    }

    @AfterAll
    public static void afterClass() {
        callbackServer.stop();
    }

    @Test
    @Timeout(15)
    public void shouldCreateEnvironmentAndSendCompletionCallback() throws URISyntaxException, InterruptedException {
        mockOpenShiftClient(false, false);

        Request callbackRequest = new Request(
                Request.Method.POST,
                new URI("http://localhost:8082/" + CallbackHandler.class.getSimpleName()),
                Collections.singletonList(new Request.Header(Headers.CONTENT_TYPE_STRING, MediaType.APPLICATION_JSON)));

        // when create the environment
        CreateRequest request = CreateRequest.builder()
                .environmentLabel("env1")
                .completionCallback(callbackRequest)
                .build();
        CreateResponse createResponse = given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .post("/create")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .as(CreateResponse.class);

        logger.info("Environment id: {}", createResponse.getEnvironmentId());

        // then
        Assertions.assertTrue(createResponse.getEnvironmentId().contains("env1"));
        Request callback = callbackRequests.take();
        EnvironmentCreationCompleted creationCompleted = mapper
                .convertValue(callback.getAttachment(), EnvironmentCreationCompleted.class);
        logger.info("Environment creation completed with status: {}", creationCompleted.getStatus());
        Assertions.assertEquals(SUCCESS, creationCompleted.getStatus(), "Unexpected environment creation status.");
        Assertions.assertEquals(2, pingRequests.size(), "Unexpected number of received pings.");
    }

    @Test
    @Timeout(5)
    public void shouldFailCreateRequest() throws URISyntaxException, InterruptedException {
        mockOpenShiftClient(true, false);

        Request callbackRequest = new Request(
                Request.Method.POST,
                new URI("http://localhost:8082/" + CallbackHandler.class.getSimpleName()),
                Collections.singletonList(new Request.Header(Headers.CONTENT_TYPE_STRING, MediaType.APPLICATION_JSON)));

        // when create the environment
        CreateRequest request = CreateRequest.builder()
                .environmentLabel("env1")
                .completionCallback(callbackRequest)
                .build();
        given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .post("/create")
                .then()
                .statusCode(500);
    }

    @Test
    @Timeout(15)
    public void shouldCallbackWithFaileResultWhenBuildAgentPingFails() throws URISyntaxException, InterruptedException {
        mockOpenShiftClient(false, true);

        Request callbackRequest = new Request(
                Request.Method.POST,
                new URI("http://localhost:8082/" + CallbackHandler.class.getSimpleName()),
                Collections.singletonList(new Request.Header(Headers.CONTENT_TYPE_STRING, MediaType.APPLICATION_JSON)));

        // when create the environment
        CreateRequest request = CreateRequest.builder()
                .environmentLabel("env1")
                .completionCallback(callbackRequest)
                .build();
        CreateResponse createResponse = given().contentType(MediaType.APPLICATION_JSON)
                .headers(requestHeaders())
                .body(request)
                .when()
                .post("/create")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .as(CreateResponse.class);

        logger.info("Environment id: {}", createResponse.getEnvironmentId());

        // then
        Request callback = callbackRequests.take();
        EnvironmentCreationCompleted creationCompleted = mapper
                .convertValue(callback.getAttachment(), EnvironmentCreationCompleted.class);
        logger.info("Environment creation completed with status: {}", creationCompleted.getStatus());
        Assertions.assertEquals(FAILED, creationCompleted.getStatus(), "Unexpected environment creation status.");
        logger.info("CreationCompleted.message: [{}]", creationCompleted.getMessage());
        Assertions.assertEquals(0, pingRequests.size(), "Unexpected number of received pings.");
    }

    private void mockOpenShiftClient(boolean failToRequestNewPod, boolean invalidServiceAddress) {
        Pod pod = mock(Pod.class, RETURNS_DEEP_STUBS);
        when(pod.getStatus().getPhase()).thenReturn("Running");
        when(pod.getMetadata().getName()).thenReturn("Mocked-pod-name");
        PodResource podResource = mock(PodResource.class);
        when(podResource.get()).thenReturn(pod);

        OpenShiftClient openShiftClientMock = mock(OpenShiftClient.class, RETURNS_DEEP_STUBS);
        MixedOperation pods = mock(MixedOperation.class);
        when(pods.withName(anyString())).thenReturn(podResource);
        if (failToRequestNewPod) {
            when(pods.create(any(Pod.class))).thenThrow(new RuntimeException("Intentionally fail to create pod."));
        } else {
            when(pods.create(any(Pod.class))).thenReturn(pod);
        }
        when(openShiftClientMock.pods()).thenReturn(pods);

        ServiceSpec spec = mock(ServiceSpec.class);
        if (invalidServiceAddress) {
            when(spec.getClusterIP()).thenReturn("127.1.2.3");
        } else {
            when(spec.getClusterIP()).thenReturn("127.0.0.1");
        }
        ServicePort servicePort = mock(ServicePort.class);
        when(servicePort.getPort()).thenReturn(8082);
        when(spec.getPorts()).thenReturn(Collections.singletonList(servicePort));
        Service service = mock(Service.class, RETURNS_DEEP_STUBS);
        when(service.getSpec()).thenReturn(spec);
        ServiceResource serviceResource = mock(ServiceResource.class);
        when(serviceResource.get()).thenReturn(service);
        when(openShiftClientMock.services().withName(anyString())).thenReturn(serviceResource);
        QuarkusMock.installMockForType(openShiftClientMock, OpenShiftClient.class);
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put(MDCHeaderKeys.PROCESS_CONTEXT.getHeaderName(), "A");
        headers.put(MDCHeaderKeys.TMP.getHeaderName(), "false");
        headers.put(MDCHeaderKeys.EXP.getHeaderName(), "0");
        headers.put(MDCHeaderKeys.USER_ID.getHeaderName(), "1");
        return headers;
    }
}
