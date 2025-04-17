/**
 * Copyright (c) 2025 the Eclipse FA³ST Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.eclipse.digitaltwin.fa3st.service.registry;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import java.net.URI;
import java.util.List;
import org.eclipse.digitaltwin.aas4j.v3.model.AssetAdministrationShell;
import org.eclipse.digitaltwin.aas4j.v3.model.Environment;
import org.eclipse.digitaltwin.aas4j.v3.model.KeyTypes;
import org.eclipse.digitaltwin.aas4j.v3.model.SecurityAttributeObject;
import org.eclipse.digitaltwin.aas4j.v3.model.SecurityTypeEnum;
import org.eclipse.digitaltwin.aas4j.v3.model.Submodel;
import org.eclipse.digitaltwin.aas4j.v3.model.SubmodelDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultAssetAdministrationShellDescriptor;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultProtocolInformation;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSecurityAttributeObject;
import org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultSubmodelDescriptor;
import org.eclipse.digitaltwin.fa3st.common.exception.MessageBusException;
import org.eclipse.digitaltwin.fa3st.common.exception.PersistenceException;
import org.eclipse.digitaltwin.fa3st.common.exception.ResourceNotFoundException;
import org.eclipse.digitaltwin.fa3st.common.model.AASFull;
import org.eclipse.digitaltwin.fa3st.common.model.api.modifier.QueryModifier;
import org.eclipse.digitaltwin.fa3st.common.model.api.paging.Page;
import org.eclipse.digitaltwin.fa3st.common.model.messagebus.event.change.ElementCreateEventMessage;
import org.eclipse.digitaltwin.fa3st.common.model.messagebus.event.change.ElementDeleteEventMessage;
import org.eclipse.digitaltwin.fa3st.common.model.messagebus.event.change.ElementUpdateEventMessage;
import org.eclipse.digitaltwin.fa3st.common.util.EncodingHelper;
import org.eclipse.digitaltwin.fa3st.common.util.LambdaExceptionHelper;
import org.eclipse.digitaltwin.fa3st.common.util.ReferenceHelper;
import org.eclipse.digitaltwin.fa3st.service.config.CoreConfig;
import org.eclipse.digitaltwin.fa3st.service.endpoint.Endpoint;
import org.eclipse.digitaltwin.fa3st.service.messagebus.MessageBus;
import org.eclipse.digitaltwin.fa3st.service.persistence.Persistence;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;


public class RegistrySynchronizationTest {
    @ClassRule
    public static WireMockClassRule wireMockRule = new WireMockClassRule(options().dynamicPort());
    @Rule
    public WireMockClassRule instanceRule = wireMockRule;

    private static final String AAS_URL_PATH = "/api/v3.0/shell-descriptors";
    private static final String SUBMODEL_URL_PATH = "/api/v3.0/submodel-descriptors";
    private ObjectMapper mapper;
    private MessageBus messageBus;
    private Endpoint endpoint;
    private Persistence persistence;
    private Environment environment;
    private URI serviceUri;
    private RegistrySynchronization registrySynchronization;

    @Before
    public void init() throws Exception {
        serviceUri = new URI("https://example.org/service/api/v3.0");
        mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
                .addMixIn(SecurityAttributeObject.class, SecurityAttributeObjectMixin.class)
                .addMixIn(org.eclipse.digitaltwin.aas4j.v3.model.Endpoint.class, EndpointMixin.class);
        mockEndpoint();
        mockMessageBus();
        mockPersistence();
        registrySynchronization = new RegistrySynchronization(
                CoreConfig.builder()
                        .aasRegistry("http://localhost:" + wireMockRule.port())
                        .submodelRegistry("http://localhost:" + wireMockRule.port())
                        .build(),
                persistence,
                messageBus,
                List.of(endpoint));
    }


    @Test
    public void testInitialRegistration() throws Exception {
        registrySynchronization.start();
        registrySynchronization.stop();

        for (AssetAdministrationShell aas: environment.getAssetAdministrationShells()) {
            verify(postRequestedFor(urlEqualTo(AAS_URL_PATH))
                    .withRequestBody(equalToJson(getAasDescriptorBody(aas), true, false)));
        }
    }


    @Test
    public void testUnregistrationOnExit() {
        registrySynchronization.start();
        registrySynchronization.stop();

        for (AssetAdministrationShell aas: environment.getAssetAdministrationShells()) {
            verify(deleteRequestedFor(urlEqualTo(AAS_URL_PATH + "/" + EncodingHelper.base64UrlEncode(aas.getId()))));
        }
    }


    @Test
    public void testAasCreation() throws MessageBusException, JsonProcessingException {
        AssetAdministrationShell aas = environment.getAssetAdministrationShells().get(0);
        registrySynchronization.start();
        messageBus.publish(ElementCreateEventMessage.builder()
                .element(aas).build());
        registrySynchronization.stop();
        verify(postRequestedFor(urlEqualTo(AAS_URL_PATH))
                .withRequestBody(equalToJson(getAasDescriptorBody(aas), true, false)));
    }


    @Test
    public void testAasUpdate() throws MessageBusException, JsonProcessingException {
        AssetAdministrationShell aas = environment.getAssetAdministrationShells().get(0);
        aas.setIdShort("Changed Id Short");
        registrySynchronization.start();
        messageBus.publish(ElementUpdateEventMessage.builder()
                .element(aas).build());
        registrySynchronization.stop();
        verify(putRequestedFor(urlEqualTo(AAS_URL_PATH + "/" + EncodingHelper.base64UrlEncode(aas.getId())))
                .withRequestBody(equalToJson(getAasDescriptorBody(aas), true, false)));
    }


    @Test
    public void testAasDeletion() throws MessageBusException {
        AssetAdministrationShell aas = environment.getAssetAdministrationShells().get(0);
        registrySynchronization.start();
        messageBus.publish(ElementDeleteEventMessage.builder()
                .element(aas).build());
        registrySynchronization.stop();
        verify(deleteRequestedFor(urlEqualTo(AAS_URL_PATH + "/" + EncodingHelper.base64UrlEncode(aas.getId()))));
    }


    @Test
    public void testSubmodelCreation() throws MessageBusException, JsonProcessingException {
        Submodel submodel = environment.getSubmodels().get(0);
        registrySynchronization.start();
        messageBus.publish(ElementCreateEventMessage.builder()
                .element(submodel).build());
        registrySynchronization.stop();
        verify(postRequestedFor(urlEqualTo(SUBMODEL_URL_PATH))
                .withRequestBody(equalToJson(getSubmodelDescriptorBody(submodel), true, false)));
    }


    @Test
    public void testSubmodelUpdate() throws MessageBusException, JsonProcessingException {
        Submodel submodel = environment.getSubmodels().get(0);
        String oldIdShort = submodel.getIdShort();
        submodel.setIdShort("Changed Id Short");
        registrySynchronization.start();
        messageBus.publish(ElementUpdateEventMessage.builder()
                .element(submodel).build());
        registrySynchronization.stop();
        verify(putRequestedFor(urlEqualTo(SUBMODEL_URL_PATH + "/" + EncodingHelper.base64UrlEncode(submodel.getId())))
                .withRequestBody(equalToJson(getSubmodelDescriptorBody(submodel), true, false)));

        submodel.setIdShort(oldIdShort);
    }


    @Test
    public void testSubmodelDeletion() throws MessageBusException {
        Submodel submodel = environment.getSubmodels().get(0);
        registrySynchronization.start();
        messageBus.publish(ElementDeleteEventMessage.builder()
                .element(submodel).build());
        registrySynchronization.stop();
        verify(deleteRequestedFor(urlEqualTo(SUBMODEL_URL_PATH + "/" + EncodingHelper.base64UrlEncode(submodel.getId()))));
    }


    private void mockEndpoint() {
        endpoint = Mockito.mock(Endpoint.class);
        doAnswer((InvocationOnMock invocation) -> {
            String aasId = invocation.getArgument(0);
            return List.of(
                    new org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultEndpoint.Builder()
                            ._interface("AAS-REPOSITORY-3.0")
                            .protocolInformation(new DefaultProtocolInformation.Builder()
                                    .href(serviceUri.toASCIIString())
                                    .endpointProtocol("HTTP")
                                    .endpointProtocolVersion("1.1")
                                    .securityAttributes(new DefaultSecurityAttributeObject.Builder()
                                            .type(SecurityTypeEnum.NONE)
                                            .key("")
                                            .value("")
                                            .build())
                                    .build())
                            .build(),
                    new org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultEndpoint.Builder()
                            ._interface("AAS-3.0")
                            .protocolInformation(new DefaultProtocolInformation.Builder()
                                    .href(serviceUri.toASCIIString() + "/shells/" + EncodingHelper.base64UrlEncode(aasId))
                                    .endpointProtocol("HTTP")
                                    .endpointProtocolVersion("1.1")
                                    .securityAttributes(new DefaultSecurityAttributeObject.Builder()
                                            .type(SecurityTypeEnum.NONE)
                                            .key("")
                                            .value("")
                                            .build())
                                    .build())
                            .build());
        }).when(endpoint).getAasEndpointInformation(any(String.class));

        doAnswer((InvocationOnMock invocation) -> {
            String submodelId = invocation.getArgument(0);
            return List.of(
                    new org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultEndpoint.Builder()
                            ._interface("SUBMODEL-REPOSITORY-3.0")
                            .protocolInformation(new DefaultProtocolInformation.Builder()
                                    .href(serviceUri.toASCIIString())
                                    .endpointProtocol("HTTP")
                                    .endpointProtocolVersion("1.1")
                                    .securityAttributes(new DefaultSecurityAttributeObject.Builder()
                                            .type(SecurityTypeEnum.NONE)
                                            .key("")
                                            .value("")
                                            .build())
                                    .build())
                            .build(),
                    new org.eclipse.digitaltwin.aas4j.v3.model.impl.DefaultEndpoint.Builder()
                            ._interface("SUBMODEL-3.0")
                            .protocolInformation(new DefaultProtocolInformation.Builder()
                                    .href(serviceUri.toASCIIString() + "/submodels/" + EncodingHelper.base64UrlEncode(submodelId))
                                    .endpointProtocol("HTTP")
                                    .endpointProtocolVersion("1.1")
                                    .securityAttributes(new DefaultSecurityAttributeObject.Builder()
                                            .type(SecurityTypeEnum.NONE)
                                            .key("")
                                            .value("")
                                            .build())
                                    .build())
                            .build());
        }).when(endpoint).getSubmodelEndpointInformation(any(String.class));

    }


    private void mockMessageBus() throws MessageBusException {
        messageBus = Mockito.mock(MessageBus.class);
        doAnswer((InvocationOnMock invocation) -> {
            ElementCreateEventMessage eventMessage = invocation.getArgument(0);
            try {
                registrySynchronization.handleCreateEvent(eventMessage);
            }
            catch (Exception e) {
                fail();
            }
            return null;
        }).when(messageBus).publish(any(ElementCreateEventMessage.class));

        doAnswer((InvocationOnMock invocation) -> {
            ElementUpdateEventMessage eventMessage = invocation.getArgument(0);
            try {
                registrySynchronization.handleChangeEvent(eventMessage);
            }
            catch (Exception e) {
                fail();
            }
            return null;
        }).when(messageBus).publish(any(ElementUpdateEventMessage.class));

        doAnswer((InvocationOnMock invocation) -> {
            ElementDeleteEventMessage eventMessage = invocation.getArgument(0);
            try {
                registrySynchronization.handleDeleteEvent(eventMessage);
            }
            catch (Exception e) {
                fail();
            }
            return null;
        }).when(messageBus).publish(any(ElementDeleteEventMessage.class));
    }


    private void mockPersistence() throws ResourceNotFoundException, PersistenceException {
        persistence = Mockito.mock(Persistence.class);
        environment = AASFull.createEnvironment();
        when(persistence.getAllAssetAdministrationShells(any(), any()))
                .thenReturn(Page.<AssetAdministrationShell> builder().result(environment.getAssetAdministrationShells()).build());

        when(persistence.getAllSubmodels(any(), any()))
                .thenReturn(Page.<Submodel> builder().result(environment.getSubmodels()).build());

        when(persistence.getSubmodel(any(String.class), any()))
                .thenAnswer((Answer<Submodel>) invocation -> {
                    String id = invocation.getArgument(0);
                    return environment.getSubmodels().stream()
                            .filter(s -> s.getId().equals(id))
                            .findFirst()
                            .orElseThrow(() -> new ResourceNotFoundException(id, Submodel.class));
                });
        when(persistence.getAssetAdministrationShell(any(String.class), any()))
                .thenAnswer((Answer<AssetAdministrationShell>) invocation -> {
                    String id = invocation.getArgument(0);
                    return environment.getAssetAdministrationShells().stream()
                            .filter(a -> a.getId().equals(id))
                            .findFirst()
                            .orElseThrow(() -> new ResourceNotFoundException(id, AssetAdministrationShell.class));
                });

        when(persistence.submodelExists(any(String.class)))
                .thenAnswer(invocation -> {
                    String id = invocation.getArgument(0);
                    return environment.getSubmodels().stream()
                            .anyMatch(a -> a.getId().equals(id));
                });
    }


    private String getAasDescriptorBody(AssetAdministrationShell aas) throws JsonProcessingException {
        return mapper.writeValueAsString(new DefaultAssetAdministrationShellDescriptor.Builder()
                .administration(aas.getAdministration())
                .id(aas.getId())
                .idShort(aas.getIdShort())
                .description(aas.getDescription())
                .globalAssetId(aas.getAssetInformation().getGlobalAssetId())
                .assetType(aas.getAssetInformation().getAssetType())
                .assetKind(aas.getAssetInformation().getAssetKind())
                .displayName(aas.getDisplayName())
                .extensions(aas.getExtensions())
                .endpoints(endpoint.getAasEndpointInformation(aas.getId()))
                .submodelDescriptors(getSubmodelDescriptorsFromAas(aas))
                .build());
    }


    private String getSubmodelDescriptorBody(Submodel submodel) throws JsonProcessingException {
        return mapper.writeValueAsString(new DefaultSubmodelDescriptor.Builder()
                .administration(submodel.getAdministration())
                .id(submodel.getId())
                .idShort(submodel.getIdShort())
                .description(submodel.getDescription())
                .semanticId(submodel.getSemanticId())
                .supplementalSemanticId(submodel.getSupplementalSemanticIds())
                .displayName(submodel.getDisplayName())
                .extensions(submodel.getExtensions())
                .endpoints(endpoint.getSubmodelEndpointInformation(submodel.getId()))
                .build());
    }


    private List<SubmodelDescriptor> getSubmodelDescriptorsFromAas(AssetAdministrationShell aas) {
        return aas.getSubmodels().stream()
                .map(x -> ReferenceHelper.findFirstKeyType(x, KeyTypes.SUBMODEL))
                .filter(persistence::submodelExists)
                .map(LambdaExceptionHelper.wrapFunction(x -> persistence.getSubmodel(x, QueryModifier.MINIMAL)))
                .map(x -> new DefaultSubmodelDescriptor.Builder()
                        .administration(x.getAdministration())
                        .id(x.getId())
                        .idShort(x.getIdShort())
                        .description(x.getDescription())
                        .semanticId(x.getSemanticId())
                        .supplementalSemanticId(x.getSupplementalSemanticIds())
                        .displayName(x.getDisplayName())
                        .extensions(x.getExtensions())
                        .endpoints(endpoint.getSubmodelEndpointInformation(x.getId()))
                        .build())
                .map(SubmodelDescriptor.class::cast)
                .toList();
    }

}
