// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.aad.msal4j;

import com.nimbusds.oauth2.sdk.util.URLUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.SocketException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ManagedIdentityTests {

    static final String resource = "https://management.azure.com";
    final static String resourceDefaultSuffix = "https://management.azure.com/.default";
    final static String appServiceEndpoint = "http://127.0.0.1:41564/msi/token";
    final static String IMDS_ENDPOINT = "http://169.254.169.254/metadata/identity/oauth2/token";
    final static String azureArcEndpoint = "http://localhost:40342/metadata/identity/oauth2/token";
    final static String cloudShellEndpoint = "http://localhost:40342/metadata/identity/oauth2/token";
    final static String serviceFabricEndpoint = "http://localhost:40342/metadata/identity/oauth2/token";
    private static ManagedIdentityApplication miApp;

    private String getSuccessfulResponse(String resource) {
        long expiresOn = (System.currentTimeMillis() / 1000) + (24 * 3600);//A long-lived, 24 hour token
        return "{\"access_token\":\"accesstoken\",\"expires_on\":\"" + expiresOn + "\",\"resource\":\"" + resource + "\",\"token_type\":" +
                "\"Bearer\",\"client_id\":\"client_id\"}";
    }

    private String getMsiErrorResponse() {
        return "{\"statusCode\":\"500\",\"message\":\"An unexpected error occured while fetching the AAD Token.\",\"correlationId\":\"7d0c9763-ff1d-4842-a3f3-6d49e64f4513\"}";
    }

    //Cloud Shell error responses follow a different style, the error info is in a second JSON
    private String getMsiErrorResponseCloudShell() {
        return "{\"error\":{\"code\":\"AudienceNotSupported\",\"message\":\"Audience user.read is not a supported MSI token audience.\"}}";
    }

    private String getMsiErrorResponseNoRetry() {
        return "{\"statusCode\":\"123\",\"message\":\"Not one of the retryable error responses\",\"correlationId\":\"7d0c9763-ff1d-4842-a3f3-6d49e64f4513\"}";
    }

    private HttpRequest expectedRequest(ManagedIdentitySourceType source, String resource) {
        return expectedRequest(source, resource, ManagedIdentityId.systemAssigned());
    }

    private HttpRequest expectedRequest(ManagedIdentitySourceType source, String resource,
            ManagedIdentityId id) {
        String endpoint = null;
        Map<String, String> headers = new HashMap<>();
        Map<String, List<String>> queryParameters = new HashMap<>();
        Map<String, List<String>> bodyParameters = new HashMap<>();

        switch (source) {
            case APP_SERVICE: {
                endpoint = appServiceEndpoint;

                queryParameters.put("api-version", Collections.singletonList("2019-08-01"));
                queryParameters.put("resource", Collections.singletonList(resource));

                headers.put("X-IDENTITY-HEADER", "secret");
                break;
            }
            case CLOUD_SHELL: {
                endpoint = cloudShellEndpoint;

                headers.put("ContentType", "application/x-www-form-urlencoded");
                headers.put("Metadata", "true");

                bodyParameters.put("resource", Collections.singletonList(resource));

                queryParameters.put("resource", Collections.singletonList(resource));
                return new HttpRequest(HttpMethod.GET, computeUri(endpoint, queryParameters), headers, URLUtils.serializeParameters(bodyParameters));
            }
            case IMDS: {
                endpoint = IMDS_ENDPOINT;
                queryParameters.put("api-version", Collections.singletonList("2018-02-01"));
                queryParameters.put("resource", Collections.singletonList(resource));
                headers.put("Metadata", "true");
                break;
            }
            case AZURE_ARC: {
                endpoint = azureArcEndpoint;

                queryParameters.put("api-version", Collections.singletonList("2019-11-01"));
                queryParameters.put("resource", Collections.singletonList(resource));

                headers.put("Metadata", "true");
                break;
            }
            case SERVICE_FABRIC:
                endpoint = serviceFabricEndpoint;
                queryParameters.put("api-version", Collections.singletonList("2019-07-01-preview"));
                queryParameters.put("resource", Collections.singletonList(resource));
                break;
        }

        switch (id.getIdType()) {
            case CLIENT_ID:
                queryParameters.put("client_id", Collections.singletonList(id.getUserAssignedId()));
                break;
            case RESOURCE_ID:
                queryParameters.put("mi_res_id", Collections.singletonList(id.getUserAssignedId()));
                break;
            case OBJECT_ID:
                queryParameters.put("object_id", Collections.singletonList(id.getUserAssignedId()));
                break;
        }

        return new HttpRequest(HttpMethod.GET, computeUri(endpoint, queryParameters), headers);
    }

    private String computeUri(String endpoint, Map<String, List<String>> queryParameters) {
        if (queryParameters.isEmpty()) {
            return endpoint;
        }

        String queryString = URLUtils.serializeParameters(queryParameters);

        return endpoint + "?" + queryString;
    }

    private HttpResponse expectedResponse(int statusCode, String response) {
        HttpResponse httpResponse = new HttpResponse();
        httpResponse.statusCode(statusCode);
        httpResponse.body(response);

        return httpResponse;
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataGetSource")
    void managedIdentity_GetManagedIdentitySource(ManagedIdentitySourceType source, String endpoint, ManagedIdentitySourceType expectedSource) {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .build();

        ManagedIdentitySourceType miClientSourceType = ManagedIdentityClient.getManagedIdentitySource();
        ManagedIdentitySourceType miAppSourceType = ManagedIdentityApplication.getManagedIdentitySource();
        assertEquals(expectedSource, miClientSourceType);
        assertEquals(expectedSource, miAppSourceType);
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createData")
    void managedIdentityTest_SystemAssigned_SuccessfulResponse(ManagedIdentitySourceType source, String endpoint, String resource) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(200, getSuccessfulResponse(resource)));

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        IAuthenticationResult result = miApp.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(resource)
                        .build()).get();

        assertNotNull(result.accessToken());

        String accessToken = result.accessToken();

        result = miApp.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(resource)
                        .build()).get();

        assertNotNull(result.accessToken());
        assertEquals(accessToken, result.accessToken());
        verify(httpClientMock, times(1)).send(any());
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataUserAssigned")
    void managedIdentityTest_UserAssigned_SuccessfulResponse(ManagedIdentitySourceType source, String endpoint, ManagedIdentityId id) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(source, resource, id))).thenReturn(expectedResponse(200, getSuccessfulResponse(resource)));

        miApp = ManagedIdentityApplication
                .builder(id)
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        IAuthenticationResult result = miApp.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(resource)
                        .build()).get();

        assertNotNull(result.accessToken());
        verify(httpClientMock, times(1)).send(any());
    }

    @Test
    void managedIdentityTest_RefreshOnHalfOfExpiresOn() throws Exception {
        //All managed identity flows use the same AcquireTokenByManagedIdentitySupplier where refreshOn is set,
        //  so any of the MI options should let us verify that it's being set correctly
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(ManagedIdentitySourceType.APP_SERVICE, appServiceEndpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(ManagedIdentitySourceType.APP_SERVICE, resource))).thenReturn(expectedResponse(200, getSuccessfulResponse(resource)));

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        AuthenticationResult result = (AuthenticationResult) miApp.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(resource)
                        .build()).get();

        long timestampSeconds = (System.currentTimeMillis() / 1000);

        assertNotNull(result.accessToken());
        assertEquals((result.expiresOn() - timestampSeconds)/2, result.refreshOn() - timestampSeconds);

        verify(httpClientMock, times(1)).send(any());
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataUserAssignedNotSupported")
    void managedIdentityTest_UserAssigned_NotSupported(ManagedIdentitySourceType source, String endpoint, ManagedIdentityId id) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        miApp = ManagedIdentityApplication
                .builder(id)
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        try {
            IAuthenticationResult result = miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception e) {
            assertNotNull(e);
            assertNotNull(e.getCause());
            assertInstanceOf(MsalServiceException.class, e.getCause());

            MsalServiceException msalMsiException = (MsalServiceException) e.getCause();
            assertEquals(source.name(), msalMsiException.managedIdentitySource());
            assertEquals(MsalError.USER_ASSIGNED_MANAGED_IDENTITY_NOT_SUPPORTED, msalMsiException.errorCode());
            return;
        }

        fail("MsalServiceException is expected but not thrown.");
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createData")
    void managedIdentityTest_DifferentScopes_RequestsNewToken(ManagedIdentitySourceType source, String endpoint) throws Exception {
        String resource = "https://management.azure.com";
        String anotherResource = "https://graph.microsoft.com";

        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(200, getSuccessfulResponse(resource)));
        when(httpClientMock.send(expectedRequest(source, anotherResource))).thenReturn(expectedResponse(200, getSuccessfulResponse(resource)));

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        IAuthenticationResult result = miApp.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(resource)
                        .build()).get();

        assertNotNull(result.accessToken());

        result = miApp.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(anotherResource)
                        .build()).get();

        assertNotNull(result.accessToken());
        verify(httpClientMock, times(2)).send(any());
        // TODO: Assert token source to check the token source is IDP and not Cache.
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataWrongScope")
    void managedIdentityTest_WrongScopes(ManagedIdentitySourceType source, String endpoint, String resource) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        if (environmentVariables.getEnvironmentVariable("SourceType").equals(ManagedIdentitySourceType.CLOUD_SHELL.toString())) {
            when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(500, getMsiErrorResponseCloudShell()));
        } else {
            when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(500, getMsiErrorResponse()));
        }

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            MsalServiceException miException = (MsalServiceException) exception.getCause();
            assertEquals(source.name(), miException.managedIdentitySource());
            assertEquals(AuthenticationErrorCode.MANAGED_IDENTITY_REQUEST_FAILED, miException.errorCode());
            return;
        }

        fail("MsalServiceException is expected but not thrown.");
        verify(httpClientMock, times(1)).send(any());
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataWrongScope")
    void managedIdentityTest_Retry(ManagedIdentitySourceType source, String endpoint, String resource) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        //Several specific 4xx and 5xx errors, such as 500, should trigger MSAL's retry logic
        when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(500, getMsiErrorResponse()));

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            //There should be three retries for certain MSI error codes, so there will be four invocations of
            // HttpClient's send method: the original call, and the three retries
            verify(httpClientMock, times(4)).send(any());
        }

        clearInvocations(httpClientMock);
        //Status codes that aren't on the list, such as 123, should not cause a retry
        when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(123, getMsiErrorResponseNoRetry()));

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            //Because there was no retry, there should only be one invocation of HttpClient's send method
            verify(httpClientMock, times(1)).send(any());

            return;
        }

        fail("MsalServiceException is expected but not thrown.");
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataError")
    void managedIdentity_RequestFailed_NoPayload(ManagedIdentitySourceType source, String endpoint) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(500, ""));

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            MsalServiceException miException = (MsalServiceException) exception.getCause();
            assertEquals(source.name(), miException.managedIdentitySource());
            assertEquals(AuthenticationErrorCode.MANAGED_IDENTITY_REQUEST_FAILED, miException.errorCode());
            return;
        }

        fail("MsalServiceException is expected but not thrown.");
        verify(httpClientMock, times(1)).send(any());
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataError")
    void managedIdentity_RequestFailed_NullResponse(ManagedIdentitySourceType source, String endpoint) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(200, ""));

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            MsalServiceException miException = (MsalServiceException) exception.getCause();
            assertEquals(source.name(), miException.managedIdentitySource());
            assertEquals(AuthenticationErrorCode.MANAGED_IDENTITY_REQUEST_FAILED, miException.errorCode());
            return;
        }

        fail("MsalServiceException is expected but not thrown.");
        verify(httpClientMock, times(1)).send(any());
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataError")
    void managedIdentity_RequestFailed_UnreachableNetwork(ManagedIdentitySourceType source, String endpoint) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(source, resource))).thenThrow(new SocketException("A socket operation was attempted to an unreachable network."));

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            MsalServiceException miException = (MsalServiceException) exception.getCause();
            assertEquals(source.name(), miException.managedIdentitySource());
            assertEquals(MsalError.MANAGED_IDENTITY_UNREACHABLE_NETWORK, miException.errorCode());
            return;
        }

        fail("MsalServiceException is expected but not thrown.");
        verify(httpClientMock, times(1)).send(any());
    }

    @Test
    void azureArcManagedIdentity_MissingAuthHeader() throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(ManagedIdentitySourceType.AZURE_ARC, azureArcEndpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        HttpResponse response = new HttpResponse();
        response.statusCode(HttpStatus.SC_UNAUTHORIZED);

        when(httpClientMock.send(any())).thenReturn(response);

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            MsalServiceException miException = (MsalServiceException) exception.getCause();
            assertEquals(ManagedIdentitySourceType.AZURE_ARC.name(), miException.managedIdentitySource());
            assertEquals(MsalError.MANAGED_IDENTITY_REQUEST_FAILED, miException.errorCode());
            assertEquals(MsalErrorMessage.MANAGED_IDENTITY_NO_CHALLENGE_ERROR, miException.getMessage());
            return;
        }

        fail("MsalServiceException is expected but not thrown.");
        verify(httpClientMock, times(1)).send(any());
    }

    @ParameterizedTest
    @MethodSource("com.microsoft.aad.msal4j.ManagedIdentityTestDataProvider#createDataError")
    void managedIdentity_SharedCache(ManagedIdentitySourceType source, String endpoint) throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(source, endpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        when(httpClientMock.send(expectedRequest(source, resource))).thenReturn(expectedResponse(200, getSuccessfulResponse(resource)));

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        ManagedIdentityApplication miApp2 = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

      IAuthenticationResult resultMiApp1 = miApp.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(resource)
                        .build()).get();

        assertNotNull(resultMiApp1.accessToken());

        IAuthenticationResult resultMiApp2 = miApp2.acquireTokenForManagedIdentity(
                ManagedIdentityParameters.builder(resource)
                        .build()).get();

        assertNotNull(resultMiApp2.accessToken());

        //acquireTokenForManagedIdentity does a cache lookup by default, and all ManagedIdentityApplication's share a cache,
        // so calling acquireTokenForManagedIdentity with the same parameters in two different ManagedIdentityApplications
        // should return the same token
        assertEquals(resultMiApp1.accessToken(), resultMiApp2.accessToken());
        verify(httpClientMock, times(1)).send(any());
    }

    @Test
    void azureArcManagedIdentity_InvalidAuthHeader() throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(ManagedIdentitySourceType.AZURE_ARC, azureArcEndpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        HttpResponse response = new HttpResponse();
        response.statusCode(HttpStatus.SC_UNAUTHORIZED);
        response.headers().put("WWW-Authenticate", Collections.singletonList("xyz"));

        when(httpClientMock.send(any())).thenReturn(response);

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        try {
            miApp.acquireTokenForManagedIdentity(
                    ManagedIdentityParameters.builder(resource)
                            .build()).get();
        } catch (Exception exception) {
            assert(exception.getCause() instanceof MsalServiceException);

            MsalServiceException miException = (MsalServiceException) exception.getCause();
            assertEquals(ManagedIdentitySourceType.AZURE_ARC.name(), miException.managedIdentitySource());
            assertEquals(MsalError.MANAGED_IDENTITY_REQUEST_FAILED, miException.errorCode());
            assertEquals(MsalErrorMessage.MANAGED_IDENTITY_INVALID_CHALLENGE, miException.getMessage());
            return;
        }

        fail("MsalServiceException is expected but not thrown.");
        verify(httpClientMock, times(1)).send(any());
    }

    @Test
    void azureArcManagedIdentityAuthheaderValidationTest() throws Exception {
        IEnvironmentVariables environmentVariables = new EnvironmentVariablesHelper(ManagedIdentitySourceType.AZURE_ARC, azureArcEndpoint);
        ManagedIdentityApplication.setEnvironmentVariables(environmentVariables);
        DefaultHttpClient httpClientMock = mock(DefaultHttpClient.class);

        //Both a missing file and an invalid path structure should throw an exception
        Path validPathWithMissingFile = Paths.get(System.getenv("ProgramData")+ "/AzureConnectedMachineAgent/Tokens/secret.key");
        Path invalidPathWithRealFile = Paths.get(this.getClass().getResource("/msi-azure-arc-secret.txt").toURI());

        // Mock 401 response that returns WWW-Authenticate header
        HttpResponse response = new HttpResponse();
        response.statusCode(HttpStatus.SC_UNAUTHORIZED);
        response.headers().put("WWW-Authenticate", Collections.singletonList("Basic realm=" + validPathWithMissingFile));

        when(httpClientMock.send(expectedRequest(ManagedIdentitySourceType.AZURE_ARC, resource))).thenReturn(response);

        miApp = ManagedIdentityApplication
                .builder(ManagedIdentityId.systemAssigned())
                .httpClient(httpClientMock)
                .build();

        // Clear caching to avoid cross test pollution.
        miApp.tokenCache().accessTokens.clear();

        CompletableFuture<IAuthenticationResult> future = miApp.acquireTokenForManagedIdentity(ManagedIdentityParameters.builder(resource).build());

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof MsalServiceException);
        assertTrue(ex.getMessage().contains(MsalErrorMessage.MANAGED_IDENTITY_INVALID_FILEPATH));

        response.headers().put("WWW-Authenticate", Collections.singletonList("Basic realm=" + invalidPathWithRealFile));

        future = miApp.acquireTokenForManagedIdentity(ManagedIdentityParameters.builder(resource).build());

        ex = assertThrows(ExecutionException.class, future::get);
        assertTrue(ex.getCause() instanceof MsalServiceException);
        assertTrue(ex.getMessage().contains(MsalErrorMessage.MANAGED_IDENTITY_INVALID_FILEPATH));
    }
}
