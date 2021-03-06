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

package de.adorsys.keycloak.config.repository;

import de.adorsys.keycloak.config.exception.ImportProcessingException;
import de.adorsys.keycloak.config.exception.KeycloakRepositoryException;
import de.adorsys.keycloak.config.exception.KeycloakVersionUnsupportedException;
import de.adorsys.keycloak.config.util.InvokeUtil;
import de.adorsys.keycloak.config.util.ResponseUtil;
import org.keycloak.admin.client.resource.AuthenticationManagementResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

@Service
public class AuthenticationFlowRepository {
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationFlowRepository.class);

    private final RealmRepository realmRepository;

    @Autowired
    public AuthenticationFlowRepository(RealmRepository realmRepository) {
        this.realmRepository = realmRepository;
    }

    public Optional<AuthenticationFlowRepresentation> searchFlowByAlias(String realm, String alias) {
        logger.trace("Try to get top-level-flow '{}' from realm '{}'", alias, realm);

        // with `AuthenticationManagementResource.getFlows()` keycloak is NOT returning all so-called top-level-flows so
        // we need a partial export
        RealmRepresentation realmExport = realmRepository.partialExport(realm, false, false);
        return realmExport.getAuthenticationFlows()
                .stream()
                .filter(flow -> flow.getAlias().equals(alias))
                .findFirst();
    }

    public AuthenticationFlowRepresentation getFlowByAlias(String realm, String alias) {
        Optional<AuthenticationFlowRepresentation> maybeFlow = searchFlowByAlias(realm, alias);

        if (maybeFlow.isPresent()) {
            return maybeFlow.get();
        }

        throw new KeycloakRepositoryException("Cannot find top-level flow: " + alias);
    }

    /**
     * creates only the top-level flow WITHOUT its executions or execution-flows
     */
    public void createTopLevelFlow(String realm, AuthenticationFlowRepresentation topLevelFlowToImport) {
        logger.trace("Create top-level-flow '{}' in realm '{}'", topLevelFlowToImport.getAlias(), realm);

        AuthenticationManagementResource flowsResource = getFlows(realm);
        try {
            Response response = flowsResource.createFlow(topLevelFlowToImport);
            ResponseUtil.validate(response);
        } catch (WebApplicationException error) {
            String errorMessage = ResponseUtil.getErrorMessage(error);

            throw new ImportProcessingException(
                    "Cannot create top-level-flow '" + topLevelFlowToImport.getAlias()
                            + "' for realm '" + realm + "'"
                            + ": " + errorMessage,
                    error
            );
        }
    }

    public void updateFlow(String realm, AuthenticationFlowRepresentation flowToImport) {
        AuthenticationManagementResource flowsResource = getFlows(realm);

        try {
            InvokeUtil.invoke(
                    flowsResource, "updateFlow",
                    new Class[]{String.class, AuthenticationFlowRepresentation.class},
                    new Object[]{flowToImport.getId(), flowToImport}
            );
        } catch (KeycloakVersionUnsupportedException error) {
            //TODO: drop if we only support keycloak 11 or later
            logger.debug("Updating description isn't supported.");
        } catch (InvocationTargetException error) {
            throw new ImportProcessingException(
                    "Cannot update top-level-flow '" + flowToImport.getAlias()
                            + "' for realm '" + realm + "'"
                            + ".",
                    error
            );
        }
    }

    public AuthenticationFlowRepresentation getFlowById(String realm, String id) {
        logger.trace("Get flow by id '{}' in realm '{}'", id, realm);

        AuthenticationManagementResource flowsResource = getFlows(realm);
        return flowsResource.getFlow(id);
    }

    public boolean isExistingFlow(String realm, String id) {
        try {
            return getFlowById(realm, id) != null;
        } catch (NotFoundException ex) {
            logger.debug("Flow with id '{}' in realm '{}' doesn't exists", id, realm);
            return false;
        }
    }

    public void deleteTopLevelFlow(String realm, String topLevelFlowId) {
        AuthenticationManagementResource flowsResource = getFlows(realm);

        try {
            flowsResource.deleteFlow(topLevelFlowId);
        } catch (ClientErrorException e) {
            throw new ImportProcessingException("Error occurred while trying to delete top-level-flow by id '" + topLevelFlowId + "' in realm '" + realm + "'", e);
        }
    }

    AuthenticationManagementResource getFlows(String realm) {
        logger.trace("Get flows-resource for realm '{}'...", realm);

        RealmResource realmResource = realmRepository.loadRealm(realm);
        AuthenticationManagementResource flows = realmResource.flows();

        logger.trace("Got flows-resource for realm '{}'", realm);

        return flows;
    }

    public List<AuthenticationFlowRepresentation> getTopLevelFlows(String realm) {
        AuthenticationManagementResource flowsResource = getFlows(realm);
        return flowsResource.getFlows();
    }

    public List<AuthenticationFlowRepresentation> getAll(String realm) {
        RealmRepresentation realmExport = realmRepository.partialExport(realm, false, false);
        return realmExport.getAuthenticationFlows();
    }
}
