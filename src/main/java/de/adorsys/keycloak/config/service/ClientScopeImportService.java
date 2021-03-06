/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2020 adorsys GmbH & Co. KG @ https://adorsys.de
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package de.adorsys.keycloak.config.service;

import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.properties.ImportConfigProperties;
import de.adorsys.keycloak.config.properties.ImportConfigProperties.ImportManagedProperties.ImportManagedPropertiesValues;
import de.adorsys.keycloak.config.repository.ClientScopeRepository;
import de.adorsys.keycloak.config.util.CloneUtil;
import de.adorsys.keycloak.config.util.ProtocolMapperUtil;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class ClientScopeImportService {
    private static final Logger logger = LoggerFactory.getLogger(ClientScopeImportService.class);

    private final ClientScopeRepository clientScopeRepository;
    private final ImportConfigProperties importConfigProperties;

    public ClientScopeImportService(ClientScopeRepository clientScopeRepository, ImportConfigProperties importConfigProperties) {
        this.clientScopeRepository = clientScopeRepository;
        this.importConfigProperties = importConfigProperties;
    }

    public void importClientScopes(RealmImport realmImport) {
        List<ClientScopeRepresentation> clientScopes = realmImport.getClientScopes();
        String realm = realmImport.getRealm();

        if (clientScopes == null) return;

        importClientScopes(realm, clientScopes);
    }

    private void importClientScopes(String realm, List<ClientScopeRepresentation> clientScopes) {
        List<ClientScopeRepresentation> existingClientScopes = clientScopeRepository.getClientScopes(realm);
        List<ClientScopeRepresentation> existingDefaultClientScopes = clientScopeRepository.getDefaultClientScopes(realm);

        if (importConfigProperties.getManaged().getClientScope() == ImportManagedPropertiesValues.FULL) {
            deleteClientScopesMissingInImport(realm, clientScopes, existingClientScopes, existingDefaultClientScopes);
        }

        createOrUpdateClientScopes(realm, clientScopes);
    }

    private void createOrUpdateClientScopes(String realm, List<ClientScopeRepresentation> clientScopes) {
        Consumer<ClientScopeRepresentation> loop = clientScope -> createOrUpdateClientScope(realm, clientScope);
        if (importConfigProperties.isParallel()) {
            clientScopes.parallelStream().forEach(loop);
        } else {
            clientScopes.forEach(loop);
        }
    }

    private void deleteClientScopesMissingInImport(String realm, List<ClientScopeRepresentation> clientScopes, List<ClientScopeRepresentation> existingClientScopes, List<ClientScopeRepresentation> existingDefaultClientScopes) {
        for (ClientScopeRepresentation existingClientScope : existingClientScopes) {
            if (isNotDefaultScope(existingClientScope.getName(), existingDefaultClientScopes) && !hasClientScopeWithName(clientScopes, existingClientScope.getName())) {
                logger.debug("Delete clientScope '{}' in realm '{}'", existingClientScope.getName(), realm);
                clientScopeRepository.deleteClientScope(realm, existingClientScope.getId());
            }
        }
    }

    private boolean isNotDefaultScope(String clientScopeName, List<ClientScopeRepresentation> existingDefaultClientScopes) {
        return existingDefaultClientScopes.stream().noneMatch(s -> Objects.equals(s.getName(), clientScopeName));
    }

    private boolean hasClientScopeWithName(List<ClientScopeRepresentation> clientScopes, String clientScopeName) {
        return clientScopes.stream().anyMatch(s -> Objects.equals(s.getName(), clientScopeName));
    }

    private void createOrUpdateClientScope(String realm, ClientScopeRepresentation clientScope) {
        String clientScopeName = clientScope.getName();

        Optional<ClientScopeRepresentation> maybeClientScope = clientScopeRepository.tryToFindClientScopeByName(realm, clientScopeName);

        if (maybeClientScope.isPresent()) {
            updateClientScopeIfNecessary(realm, clientScope);
        } else {
            logger.debug("Create clientScope '{}' in realm '{}'", clientScopeName, realm);
            createClientScope(realm, clientScope);
        }
    }

    private void createClientScope(String realm, ClientScopeRepresentation clientScope) {
        clientScopeRepository.createClientScope(realm, clientScope);
    }

    private void updateClientScopeIfNecessary(String realm, ClientScopeRepresentation clientScope) {
        ClientScopeRepresentation existingClientScope = clientScopeRepository.getClientScopeByName(realm, clientScope.getName());
        ClientScopeRepresentation patchedClientScope = CloneUtil.patch(existingClientScope, clientScope, "id");
        String clientScopeName = existingClientScope.getName();

        if (isClientScopeEqual(existingClientScope, patchedClientScope)) {
            logger.debug("No need to update clientScope '{}' in realm '{}'", clientScopeName, realm);
        } else {
            logger.debug("Update clientScope '{}' in realm '{}'", clientScopeName, realm);
            updateClientScope(realm, patchedClientScope);
        }
    }

    private boolean isClientScopeEqual(ClientScopeRepresentation existingClientScope, ClientScopeRepresentation patchedClientScope) {
        return CloneUtil.deepEquals(existingClientScope, patchedClientScope, "protocolMappers")
                && ProtocolMapperUtil.areProtocolMappersEqual(patchedClientScope.getProtocolMappers(), existingClientScope.getProtocolMappers());
    }

    private void updateClientScope(String realm, ClientScopeRepresentation patchedClientScope) {
        clientScopeRepository.updateClientScope(realm, patchedClientScope);

        List<ProtocolMapperRepresentation> protocolMappers = patchedClientScope.getProtocolMappers();
        if (protocolMappers != null) {
            String clientScopeId = patchedClientScope.getId();
            updateProtocolMappers(realm, clientScopeId, protocolMappers);
        }
    }

    private void updateProtocolMappers(String realm, String clientScopeId, List<ProtocolMapperRepresentation> protocolMappers) {
        ClientScopeRepresentation existingClientScope = clientScopeRepository.getClientScopeById(realm, clientScopeId);

        List<ProtocolMapperRepresentation> existingProtocolMappers = existingClientScope.getProtocolMappers();

        List<ProtocolMapperRepresentation> protocolMappersToAdd = ProtocolMapperUtil.estimateProtocolMappersToAdd(protocolMappers, existingProtocolMappers);
        List<ProtocolMapperRepresentation> protocolMappersToRemove = ProtocolMapperUtil.estimateProtocolMappersToRemove(protocolMappers, existingProtocolMappers);
        List<ProtocolMapperRepresentation> protocolMappersToUpdate = ProtocolMapperUtil.estimateProtocolMappersToUpdate(protocolMappers, existingProtocolMappers);

        clientScopeRepository.addProtocolMappers(realm, clientScopeId, protocolMappersToAdd);
        clientScopeRepository.removeProtocolMappers(realm, clientScopeId, protocolMappersToRemove);
        clientScopeRepository.updateProtocolMappers(realm, clientScopeId, protocolMappersToUpdate);
    }
}
