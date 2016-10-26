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

package org.entcore.feeder.timetable.edt;

import fr.wseduc.mongodb.MongoDb;
import fr.wseduc.webutils.DefaultAsyncResult;
import org.entcore.feeder.exceptions.TransactionException;
import org.entcore.feeder.exceptions.ValidationException;
import org.entcore.feeder.timetable.AbstractTimetableImporter;
import org.entcore.feeder.timetable.Slot;
import org.entcore.feeder.utils.JsonUtil;
import org.entcore.feeder.utils.Report;
import org.entcore.feeder.utils.TransactionHelper;
import org.entcore.feeder.utils.TransactionManager;
import org.joda.time.DateTime;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLReaderFactory;

import java.io.StringReader;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static fr.wseduc.webutils.Utils.getOrElse;
import static fr.wseduc.webutils.Utils.isNotEmpty;
import static org.entcore.feeder.dictionary.structures.DefaultProfiles.PERSONNEL_PROFILE_EXTERNAL_ID;
import static org.entcore.feeder.dictionary.structures.DefaultProfiles.TEACHER_PROFILE_EXTERNAL_ID;

public class EDTImporter extends AbstractTimetableImporter {

	private static final String MATCH_PERSEDUCNAT_QUERY =
			"MATCH (:Structure {UAI : {UAI}})<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
			"WHERE head(u.profiles) = {profile} AND LOWER(u.lastName) = {lastName} AND LOWER(u.firstName) = {firstName} " +
			"SET u.IDPN = {IDPN} " +
			"RETURN DISTINCT u.id as id, u.IDPN as IDPN, head(u.profiles) as profile";
	private static final String STUDENTS_TO_GROUPS =
			"MATCH (u:User {attachmentId = {idSconet}}), (fg:FunctionalGroup {externalId:{externalId}}) " +
			"MERGE u-[:IN {source:{source}, inDate:{inDate}, outDate:{outDate}}]->fg ";
	private static final String DELETE_OLD_RELATIONSHIPS =
			"MATCH (:Structure {id:{id}})<-[:DEPENDS]-(fg:FunctionalGroup)<-[r:IN]-(:User) " +
			"WHERE r.source = {source} AND HAS(r.outDate) AND r.outDate < {now} " +
			"DELETE r ";
	public static final String IDENT = "Ident";
	public static final String IDPN = "IDPN";
	public static final String EDT = "EDT";
	private final List<String> ignoreAttributes = Arrays.asList("Etiquette", "Periode", "PartieDeClasse");
	private final EDTUtils edtUtils;
	private final Map<String, JsonObject> notFoundPersEducNat = new HashMap<>();
//	private final Map<String, String> personnelsMapping = new HashMap<>();
	private final Map<String, String> equipments = new HashMap<>();
	private final Map<String, String> personnels = new HashMap<>();
	private final Map<String, JsonObject> subClasses = new HashMap<>();

	public EDTImporter(EDTUtils edtUtils, String uai, String acceptLanguage) {
		super(uai, acceptLanguage);
		this.edtUtils = edtUtils;
	}

	public void launch(final AsyncResultHandler<Report> handler) throws Exception {
		final String content = edtUtils.decryptExport("/home/dboissin/Docs/EDT - UDT/Edt_To_NEO-Open_1234567H.xml");
		init(new AsyncResultHandler<Void>() {
			@Override
			public void handle(AsyncResult<Void> event) {
				if (event.succeeded()) {
					try {
						txXDT.setAutoSend(false);
						parse(content, true);
						if (txXDT.isEmpty()) {
							parse(content, false);
						} else {
							matchAndCreatePersEducNat(new AsyncResultHandler<Void>() {
								@Override
								public void handle(AsyncResult<Void> event) {
									if (event.succeeded()) {
										try {
											txXDT = TransactionManager.getTransaction();
											parse(content, false);
											txXDT.add(DELETE_OLD_RELATIONSHIPS, new JsonObject()
													.putString("id", structureId).putString("source", EDT)
													.putNumber("now", System.currentTimeMillis()));
											commit(handler);
										} catch (Exception e) {
											handler.handle(new DefaultAsyncResult<Report>(e));
										}
									} else {
										handler.handle(new DefaultAsyncResult<Report>(event.cause()));
									}
								}
							});
						}
					} catch (Exception e) {
						handler.handle(new DefaultAsyncResult<Report>(e));
					}
				} else {
					handler.handle(new DefaultAsyncResult<Report>(event.cause()));
				}
			}
		});
	}

	private void parse(String content, boolean persEducNatOnly) throws Exception {
		//InputSource in = new InputSource("/home/dboissin/Docs/EDT - UDT/ImportCahierTexte/EDT/HarounTazieff.xml");
		InputSource in = new InputSource(new StringReader(content));
		EDTHandler sh = new EDTHandler(this, persEducNatOnly);
		XMLReader xr = XMLReaderFactory.createXMLReader();
		xr.setContentHandler(sh);
		xr.parse(in);
	}

	void initSchoolYear(JsonObject schoolYear) {
		startDateWeek1 = DateTime.parse(schoolYear.getString("DatePremierJourSemaine1"));
	}

	void initSchedule(JsonObject currentEntity) {
		slotDuration = Integer.parseInt(currentEntity.getString("DureePlace")) * 60;
		for (Object o : currentEntity.getArray("Place")) {
			if (o instanceof JsonObject) {
				JsonObject s = (JsonObject) o;
				slots.put(s.getString("Numero"), new Slot(
						s.getString("LibelleHeureDebut"), s.getString("LibelleHeureFin"), slotDuration));
			}
		}
	}

	void addRoom(JsonObject currentEntity) {
		rooms.put(currentEntity.getString(IDENT), currentEntity.getString("Nom"));
	}

	void addEquipment(JsonObject currentEntity) {
		equipments.put(currentEntity.getString(IDENT), currentEntity.getString("Nom"));
	}

	void addSubject(JsonObject currentEntity) {
		super.addSubject(currentEntity.getString(IDENT), currentEntity);
	}

	void addGroup(JsonObject currentEntity) {
		final String id = currentEntity.getString(IDENT);
		groups.put(id, currentEntity);
		final JsonArray classes = currentEntity.getArray("Classe");
		final JsonArray pcs = currentEntity.getArray("PartieDeClasse");
		classInGroups(id, classes, this.classes);
		classInGroups(id, pcs, this.subClasses);

		final String name = currentEntity.getString("Nom");
		txXDT.add(CREATE_GROUPS, new JsonObject().putString("structureExternalId", structureExternalId).putString("name", name)
				.putString("externalId", structureExternalId + "$" + name).putString("id", UUID.randomUUID().toString()));
	}

	private void classInGroups(String id, JsonArray classes, Map<String, JsonObject> ref) {
		if (classes != null) {
			for (Object o : classes) {
				if (o instanceof JsonObject) {
					final JsonObject j = ref.get(((JsonObject) o).getString(IDENT));
					if (j != null) {
						JsonArray groups = j.getArray("groups");
						if (groups == null) {
							groups = new JsonArray();
							j.putArray("groups", groups);
						}
						groups.add(id);
					}
				}
			}
		}
	}

	void addClasse(JsonObject currentEntity) {
		final String id = currentEntity.getString(IDENT);
		classes.put(id, currentEntity);
		final JsonArray pcs = currentEntity.getArray("PartieDeClasse");
		final String ocn = currentEntity.getString("Nom");
		final String className = (classesMapping != null) ? getOrElse(classesMapping.getString(ocn), ocn, false) : ocn;
		currentEntity.putString("className", className);
		if (pcs != null) {
			for (Object o : pcs) {
				if (o instanceof JsonObject) {
					final String pcIdent = ((JsonObject) o).getString(IDENT);
					subClasses.put(pcIdent, ((JsonObject) o).putString("className", className));
				}
			}
		}
		txXDT.add(UNKNOWN_CLASSES, new JsonObject().putString("UAI", UAI).putString("className", className));
	}

	void addProfesseur(JsonObject currentEntity) {
		// TODO manage users without IDPN
		final String id = currentEntity.getString(IDENT);
		final String idPronote = structureExternalId + "$" + currentEntity.getString(IDPN);
		final String teacherId = teachersMapping.get(idPronote);
		if (teacherId != null) {
			teachers.put(id, teacherId);
		} else {
			findPersEducNat(currentEntity, idPronote, "Teacher");
		}
	}

	void addPersonnel(JsonObject currentEntity) {
		final String id = currentEntity.getString(IDENT);
		final String idPronote = structureExternalId + "$" + id; // fake pronote id // TODO replace by hash
//		final String personnelId = personnelsMapping.get(idPronote);
//		if (personnelId != null) {
//			personnels.put(id, personnelId);
//		} else {
		findPersEducNat(currentEntity, idPronote, "Personnel");
//		}
	}

	private void findPersEducNat(JsonObject currentEntity, String idPronote, String profile) {
		log.info(currentEntity);
		try {
			JsonObject p = persEducNat.applyMapping(currentEntity);
			p.putArray("profiles", new JsonArray().add(profile));
			p.putString("externalId", idPronote);
			p.putString(IDPN, idPronote);
			notFoundPersEducNat.put(idPronote, p);
			txXDT.add(MATCH_PERSEDUCNAT_QUERY, new JsonObject().putString("UAI", UAI).putString(IDPN, idPronote)
					.putString("profile", profile)
					.putString("lastName", p.getString("lastName").toLowerCase())
					.putString("firstName", p.getString("firstName").toLowerCase()));
		} catch (Exception e) {
			report.addError(e.getMessage());
		}
	}

	private void matchAndCreatePersEducNat(final AsyncResultHandler<Void> handler) {
		txXDT.commit(new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				JsonArray res = event.body().getArray("results");
				if ("ok".equals(event.body().getString("status")) && res != null) {
					for (Object o : res) {
						setUsersId(o);
					}
					log.info("find : " + res.encodePrettily());
					if (!notFoundPersEducNat.isEmpty()) {
						try {
							TransactionHelper tx = TransactionManager.getTransaction();
							persEducNat.setTransactionHelper(tx);
							for (JsonObject p : notFoundPersEducNat.values()) {
								if ("Teacher".equals(p.getArray("profiles").<String>get(0))){
									persEducNat.createOrUpdatePersonnel(p, TEACHER_PROFILE_EXTERNAL_ID, structure, null, null, true, true);
								} else {
									persEducNat.createOrUpdatePersonnel(p, PERSONNEL_PROFILE_EXTERNAL_ID, structure, null, null, true, true);
								}
							}
							tx.commit(new Handler<Message<JsonObject>>() {
								@Override
								public void handle(Message<JsonObject> event) {
									JsonArray res = event.body().getArray("results");
									log.info("upsert : " + res.encodePrettily());
									if ("ok".equals(event.body().getString("status")) && res != null) {
										for (Object o : res) {
											setUsersId(o);
										}
										if (notFoundPersEducNat.isEmpty()) {
											handler.handle(new DefaultAsyncResult<>((Void) null));
										} else {
											handler.handle(new DefaultAsyncResult<Void>(new ValidationException("not.found.users.not.empty")));
										}
									} else {
										handler.handle(new DefaultAsyncResult<Void>(new TransactionException(event.body()
												.getString("message"))));
									}
								}
							});
						} catch (TransactionException e) {
							handler.handle(new DefaultAsyncResult<Void>(e));
						}
					} else {
						handler.handle(new DefaultAsyncResult<>((Void) null));
					}
				} else {
					handler.handle(new DefaultAsyncResult<Void>(new TransactionException(event.body().getString("message"))));
				}
			}

			private void setUsersId(Object o) {
				if ((o instanceof JsonArray) && ((JsonArray) o).size() > 0) {
					JsonObject j = ((JsonArray) o).get(0);
					String idPronote = j.getString(IDPN);
					String id = j.getString("id");
					String profile = j.getString("profile");
					if (isNotEmpty(id) && isNotEmpty(idPronote) && isNotEmpty(profile)) {
						notFoundPersEducNat.remove(idPronote);
						if ("Teacher".equals(profile)) {
							teachersMapping.put(idPronote, id);
						} else {
							String[] ident = idPronote.split("\\$");
							if (ident.length == 2) {
								personnels.put(ident[1], id);
							}
//							personnelsMapping.put(idPronote, id);
						}
					}
				}
			}
		});

	}

	void addEleve(JsonObject currentEntity) {
		final String sconetId = currentEntity.getString("IDSconet");
		if (isNotEmpty(sconetId)) {
			final JsonArray classes = currentEntity.getArray("Classe");
			final JsonArray pcs = currentEntity.getArray("PartieDeClasse");
			studentToGroups(sconetId, classes, this.classes);
			studentToGroups(sconetId, pcs, this.subClasses);
		} else {
			// TODO :'( il faut trouver autre chose pour faire le rattachement
		}
	}

	private void studentToGroups(String sconetId, JsonArray classes, Map<String, JsonObject> ref) {
		if (classes != null) {
			for (Object o : classes) {
				if (o instanceof JsonObject) {
					final String inDate = ((JsonObject) o).getString("DateEntree");
					final String outDate = ((JsonObject) o).getString("DateSortie");
					final String ident = ((JsonObject) o).getString(IDENT);
					if (inDate == null || ident == null || outDate == null || DateTime.parse(inDate).isAfterNow()) continue;
					final JsonObject j = ref.get(ident);
					if (j != null) {
						JsonArray groups = j.getArray("groups");
						if (groups != null) {
							for (Object o2: groups) {
								JsonObject group = this.groups.get(o2.toString());
								if (group != null) {
									String name = group.getString("Nom");
									txXDT.add(STUDENTS_TO_GROUPS, new JsonObject()
											.putString("idSconet", sconetId)
											.putString("externalId", structureExternalId + "$" + name)
											.putString("source", EDT)
											.putNumber("inDate", DateTime.parse(inDate).getMillis())
											.putNumber("outDate", DateTime.parse(outDate).getMillis()));
								}
							}
						}
					}
				}
			}
		}
	}

	void addCourse(JsonObject currentEntity) {
		final List<Long> weeks = new ArrayList<>();
		final List<JsonObject> items = new ArrayList<>();

		for (String attr: currentEntity.getFieldNames()) {
			if (!ignoreAttributes.contains(attr) && currentEntity.getValue(attr) instanceof JsonArray) {
				for (Object o: currentEntity.getArray(attr)) {
					if (!(o instanceof JsonObject)) continue;
					final JsonObject j = (JsonObject) o;
					j.putString("itemType", attr);
					final String week = j.getString("Semaines");
					if (week != null) {
						weeks.add(Long.valueOf(week));
						items.add(j);
					}
				}
			}
		}

		if (currentEntity.containsField("SemainesAnnulation")) {
			log.info(currentEntity.encode());
		}
		final Long cancelWeek = (currentEntity.getString("SemainesAnnulation") != null) ?
				Long.valueOf(currentEntity.getString("SemainesAnnulation")) : null;
		BitSet lastWeek = new BitSet(weeks.size());
		int startCourseWeek = 0;
		for (int i = 1; i < 53; i++) {
			final BitSet currentWeek = new BitSet(weeks.size());
			boolean enabledCurrentWeek = false;
			for (int j = 0; j < weeks.size(); j++) {
				if (cancelWeek != null && ((1L << i) & cancelWeek) != 0) {
					currentWeek.set(j, false);
				} else {
					final Long week = weeks.get(j);
					currentWeek.set(j, ((1L << i) & week) != 0);
				}
				enabledCurrentWeek = enabledCurrentWeek | currentWeek.get(j);
			}
			if (!currentWeek.equals(lastWeek)) {
				if (startCourseWeek > 0) {
					persistCourse(generateCourse(startCourseWeek, i - 1, lastWeek, items, currentEntity));
				}
				startCourseWeek = enabledCurrentWeek ? i : 0;
				lastWeek = currentWeek;
			}
		}
	}

	private JsonObject generateCourse(int startCourseWeek, int endCourseWeek, BitSet enabledItems, List<JsonObject> items, JsonObject entity) {
		final int day = Integer.parseInt(entity.getString("Jour"));
		final int startPlace = Integer.parseInt(entity.getString("NumeroPlaceDebut"));
		final int placesNumber = Integer.parseInt(entity.getString("NombrePlaces"));
		DateTime startDate = startDateWeek1.plusWeeks(startCourseWeek - 1).plusDays(day - 1);
		DateTime endDate = startDate.plusWeeks(endCourseWeek - startCourseWeek);
		startDate = startDate.plusSeconds(slots.get(entity.getString("NumeroPlaceDebut")).getStart());
		endDate = endDate.plusSeconds(slots.get(String.valueOf((startPlace + placesNumber - 1))).getEnd());
		final JsonObject c = new JsonObject()
				.putString("structureId", structureId)
				.putString("subjectId", subjects.get(entity.getArray("Matiere").<JsonObject>get(0).getString(IDENT)))
				.putString("startDate", startDate.toString())
				.putString("endDate", endDate.toString())
				.putNumber("dayOfWeek", startDate.getDayOfWeek());

		for (int i = 0; i < enabledItems.size(); i++) {
			if (enabledItems.get(i)) {
				final JsonObject item = items.get(i);
				final String ident = item.getString(IDENT);
				switch (item.getString("itemType")) {
					case "Professeur":
						JsonArray teachersArray = c.getArray("teacherIds");
						if (teachersArray == null) {
							teachersArray = new JsonArray();
							c.putArray("teacherIds", teachersArray);
						}
						teachersArray.add(personnels.get(ident));
						break;
					case "Classe":
						JsonArray classesArray = c.getArray("classes");
						if (classesArray == null) {
							classesArray = new JsonArray();
							c.putArray("classes", classesArray);
						}
						classesArray.add(classes.get(ident).getString("className"));
						break;
					case "Groupe":
						JsonArray groupsArray = c.getArray("groups");
						if (groupsArray == null) {
							groupsArray = new JsonArray();
							c.putArray("groups", groupsArray);
						}
						groupsArray.add(groups.get(ident).getString("Nom"));
						break;
					case "Materiel":
						JsonArray equipmentsArray = c.getArray("equipmentLabels");
						if (equipmentsArray == null) {
							equipmentsArray = new JsonArray();
							c.putArray("equipmentLabels", equipmentsArray);
						}
						equipmentsArray.add(equipments.get(ident));
						break;
					case "Salle":
						JsonArray roomsArray = c.getArray("roomLabels");
						if (roomsArray == null) {
							roomsArray = new JsonArray();
							c.putArray("roomLabels", roomsArray);
						}
						roomsArray.add(rooms.get(ident));
						break;
					case "Personnel":
						JsonArray personnelsArray = c.getArray("personnelIds");
						if (personnelsArray == null) {
							personnelsArray = new JsonArray();
							c.putArray("personnelIds", personnelsArray);
						}
						personnelsArray.add(personnels.get(ident));
						break;
				}
			}
		}
		try {
			c.putString("_id", JsonUtil.checksum(c));
		} catch (NoSuchAlgorithmException e) {
			log.error("Error generating course checksum", e);
		}
		c.putNumber("modified", importTimestamp);
		return c;
	}

	@Override
	protected String getSource() {
		return EDT;
	}

	@Override
	protected String getTeacherMappingAttribute() {
		return "IDPN";
	}

}
