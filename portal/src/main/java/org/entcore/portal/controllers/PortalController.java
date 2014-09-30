/* Copyright © WebServices pour l'Éducation, 2014
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
 *
 */

package org.entcore.portal.controllers;

import fr.wseduc.rs.Get;
import fr.wseduc.webutils.http.BaseController;
import fr.wseduc.webutils.http.StaticResource;
import org.entcore.common.neo4j.Neo4j;
import org.entcore.common.user.UserUtils;
import org.entcore.common.user.UserInfos;
import org.entcore.portal.utils.ThemeUtils;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.file.FileProps;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.RouteMatcher;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Container;

import static org.entcore.common.user.SessionAttributes.*;

public class PortalController extends BaseController {

	private ConcurrentMap<String, String> staticRessources;
	private boolean dev;
	private List<String> themes;
	private String themesPrefix;
	private String assetsPath;

	@Override
	public void init(Vertx vertx, Container container, RouteMatcher rm,
			Map<String, fr.wseduc.webutils.security.SecuredAction> securedActions) {
		super.init(vertx, container, rm, securedActions);
		this.staticRessources = vertx.sharedData().getMap("staticRessources");
		dev = "dev".equals(container.config().getString("mode"));
		assetsPath = container.config().getString("assets-path", ".");
		themesPrefix = "/assets/themes/" + container.config().getString("skin");
		ThemeUtils.availableThemes(vertx, assetsPath + themesPrefix, false, new Handler<List<String>>() {
			@Override
			public void handle(List<String> event) {
				themes = event;
			}
		});
	}

	@Get("/welcome")
	@SecuredAction(value = "portal.auth",type = ActionType.AUTHENTICATED)
	public void welcome(HttpServerRequest request) {
		renderView(request);
	}

	@Get("/")
	@SecuredAction(value = "portal.auth",type = ActionType.AUTHENTICATED)
	public void portal(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {

			@Override
			public void handle(UserInfos user) {
				if (user != null) {
					redirectPermanent(request, container.config().getString("root-page", "/welcome"));
				} else {
					unauthorized(request);
				}
			}
		});
	}

	@Get("/applications-list")
	@SecuredAction(value = "portal.auth",type = ActionType.AUTHENTICATED)
	public void applicationsList(final HttpServerRequest request) {
		UserUtils.getSession(eb, request, new Handler<JsonObject>() {

			@Override
			public void handle(JsonObject session) {
				JsonArray apps = session.getArray("apps", new JsonArray());
				for (Object o : apps) {
					if (!(o instanceof JsonObject)) continue;
					JsonObject j = (JsonObject) o;
					String d = j.getString("displayName");
					if (d == null || d.trim().isEmpty()) {
						d = j.getString("name");
					}
					if (d != null) {
						j.putString("displayName", d);
					}
				}
				JsonObject json = new JsonObject()
						.putArray("apps", apps);
				renderJson(request, json);
			}
		});
	}

	@Get("/adapter")
	@SecuredAction(value = "portal.auth",type = ActionType.AUTHENTICATED)
	public void adapter(final HttpServerRequest request) {
		renderView(request);
	}

	@Get(value = "/assets/.+", regex = true)
	public void assets(final HttpServerRequest request) {
		String path = assetsPath + request.path();
		if (dev) {
			request.response().sendFile(path);
		} else {
			sendWithLastModified(request, path);
		}
	}

	@Get(value = "/current/assets/.+", regex = true)
	public void currentAssets(final HttpServerRequest request) {
		final String path = assetsPath + themesPrefix + request.path().substring(15);
		if (dev) {
			request.response().sendFile(path);
		} else {
			sendWithLastModified(request, path);
		}
	}

	private void sendWithLastModified(final HttpServerRequest request, final String path) {
		if (staticRessources.containsKey(request.uri())) {
			StaticResource.serveRessource(request,
					path,
					staticRessources.get(request.uri()));
		} else {
			vertx.fileSystem().props(path,
					new Handler<AsyncResult<FileProps>>(){
				@Override
				public void handle(AsyncResult<FileProps> af) {
					if (af.succeeded()) {
						String lastModified = StaticResource.formatDate(af.result().lastModifiedTime());
						staticRessources.put(request.uri(), lastModified);
						StaticResource.serveRessource(request,
								path,
								lastModified);
					} else {
						request.response().sendFile(path);
					}
				}
			});
		}
	}

	@Get("/theme")
	@SecuredAction(value = "portal", type = ActionType.AUTHENTICATED)
	public void getTheme(final HttpServerRequest request) {
		UserUtils.getUserInfos(eb, request, new Handler<UserInfos>() {
			@Override
			public void handle(final UserInfos user) {
				if (user != null) {
					Object t = user.getAttribute(THEME_ATTRIBUTE);
					if (t != null) {
						renderJson(request, new JsonObject(t.toString()));
						return;
					}
					JsonObject urls = container.config().getObject("urls", new JsonObject());
					final JsonObject theme = new JsonObject()
							.putString("template", "/public/template/portal.html")
							.putString("logoutCallback", urls.getString("logoutCallback", ""));
					String query =
							"MATCH (n:User)-[:USERBOOK]->u " +
							"WHERE n.id = {id} " +
							"RETURN u.theme as theme";
					Map<String, Object> params = new HashMap<>();
					params.put("id", user.getUserId());
					Neo4j.getInstance().execute(query, params, new Handler<Message<JsonObject>>() {
						@Override
						public void handle(Message<JsonObject> event) {
							if ("ok".equals(event.body().getString("status"))) {
								JsonArray result = event.body().getArray("result");
								String userTheme = (result != null && result.size() == 1) ?
										result.<JsonObject>get(0).getString("theme") : null;
								if (userTheme != null && themes.contains(userTheme)) {
									theme.putString("skin", themesPrefix + "/" + userTheme + "/");
								} else {
									theme.putString("skin", themesPrefix + "/default/");
								}
							} else {
								theme.putString("skin", themesPrefix + "/default/");
							}
							renderJson(request, theme);
							UserUtils.addSessionAttribute(eb, user.getUserId(), THEME_ATTRIBUTE, theme.encode(), null);
						}
					});
				} else {
					unauthorized(request);
				}
			}
		});
	}

	@Get("/skin")
	public void getSkin(final HttpServerRequest request) {
		renderJson(request, new JsonObject().putString("skin", container.config().getString("skin")));
	}

	@Get("/locale")
	public void locale(HttpServerRequest request) {
		String lang = request.headers().get("Accept-Language");
		if (lang == null) {
			lang = "fr";
		}
		String[] langs = lang.split(",");
		renderJson(request, new JsonObject().putString("locale",
				Locale.forLanguageTag(langs[0].split("-")[0]).toString()));
	}

	@Get("/admin-urls")
	@SecuredAction(value = "config", type = ActionType.AUTHENTICATED)
	public void adminURLS(HttpServerRequest request){
		renderJson(request, container.config().getArray("admin-urls", new JsonArray()));
	}

	@Get("/resources-applications")
	@SecuredAction(value = "config", type = ActionType.AUTHENTICATED)
	public void resourcesApplications(HttpServerRequest request){
		renderJson(request, container.config().getArray("resources-applications", new JsonArray()));
	}

	@Get("/widgets")
	@SecuredAction(value = "config", type = ActionType.AUTHENTICATED)
	public void widgets(HttpServerRequest request){
		renderJson(request, container.config().getArray("widgets", new JsonArray()));
	}

	@Get("/themes")
	@SecuredAction(value = "config", type = ActionType.AUTHENTICATED)
	public void themes(HttpServerRequest request){
		renderJson(request, container.config().getArray("themes", new JsonArray()));
	}

	@Get("/admin")
	@SecuredAction("admin.view")
	public void admin(HttpServerRequest request) {
		redirectPermanent(request, "/directory/admin");
	}

}