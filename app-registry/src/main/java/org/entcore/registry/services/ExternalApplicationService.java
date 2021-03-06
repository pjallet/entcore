/*
 * Copyright © WebServices pour l'Éducation, 2016
 *
 * This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation (version 3 of the License).
 *
 * For the sake of explanation, any module that communicate over native
 * Web protocols, such as HTTP, with ENT Core is outside the scope of this
 * license and could be license under its own terms. This is merely considered
 * normal use of ENT Core, and does not fall under the heading of "covered work".
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.entcore.registry.services;

import java.util.List;

import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import fr.wseduc.webutils.Either;

public interface ExternalApplicationService {

	void listExternalApps(String structureId, Handler<Either<String, JsonArray>> handler);
	void deleteExternalApplication(String applicationId, Handler<Either<String, JsonObject>> handler);
	void createExternalApplication(String structureId, JsonObject application, Handler<Either<String, JsonObject>> handler);
	void toggleLock(String structureId, Handler<Either<String, JsonObject>> handler);
	void massAuthorize(String appId, List<String> profiles, Handler<Either<String, JsonObject>> handler);
	void massUnauthorize(String appId, List<String> profiles, Handler<Either<String, JsonObject>> handler);

}
