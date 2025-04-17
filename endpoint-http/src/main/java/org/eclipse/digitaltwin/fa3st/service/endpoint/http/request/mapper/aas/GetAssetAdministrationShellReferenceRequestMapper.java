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
package org.eclipse.digitaltwin.fa3st.service.endpoint.http.request.mapper.aas;

import java.util.Map;
import org.eclipse.digitaltwin.fa3st.common.exception.InvalidRequestException;
import org.eclipse.digitaltwin.fa3st.common.model.api.modifier.Content;
import org.eclipse.digitaltwin.fa3st.common.model.api.modifier.OutputModifier;
import org.eclipse.digitaltwin.fa3st.common.model.api.request.aas.GetAssetAdministrationShellReferenceRequest;
import org.eclipse.digitaltwin.fa3st.common.model.api.response.aas.GetAssetAdministrationShellReferenceResponse;
import org.eclipse.digitaltwin.fa3st.common.model.http.HttpMethod;
import org.eclipse.digitaltwin.fa3st.common.util.RegExHelper;
import org.eclipse.digitaltwin.fa3st.service.ServiceContext;
import org.eclipse.digitaltwin.fa3st.service.endpoint.http.model.HttpRequest;
import org.eclipse.digitaltwin.fa3st.service.endpoint.http.request.mapper.AbstractRequestMapperWithOutputModifier;


/**
 * class to map HTTP-GET-Request path: shells/{aasIdentifier}/$reference.
 */
public class GetAssetAdministrationShellReferenceRequestMapper
        extends AbstractRequestMapperWithOutputModifier<GetAssetAdministrationShellReferenceRequest, GetAssetAdministrationShellReferenceResponse> {

    private static final String AAS_ID = RegExHelper.uniqueGroupName();
    private static final String PATTERN = String.format("shells/%s/\\$reference", pathElement(AAS_ID));

    public GetAssetAdministrationShellReferenceRequestMapper(ServiceContext serviceContext) {
        super(serviceContext, HttpMethod.GET, PATTERN, Content.METADATA, Content.NORMAL, Content.PATH, Content.VALUE);
    }


    @Override
    public GetAssetAdministrationShellReferenceRequest doParse(HttpRequest httpRequest, Map<String, String> urlParameters, OutputModifier outputModifier)
            throws InvalidRequestException {
        return GetAssetAdministrationShellReferenceRequest.builder()
                .id(getParameterBase64UrlEncoded(urlParameters, AAS_ID))
                .build();
    }
}
