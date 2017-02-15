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

package org.entcore.feeder.csv;

import au.com.bytecode.opencsv.CSV;
import au.com.bytecode.opencsv.CSVReadProc;
import org.entcore.feeder.utils.*;
import org.entcore.feeder.ImportValidator;
import org.entcore.feeder.dictionary.structures.Structure;
import org.entcore.feeder.utils.Joiner;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

import static org.entcore.feeder.be1d.Be1dFeeder.frenchDatePatter;
import static org.entcore.feeder.be1d.Be1dFeeder.generateUserExternalId;

public class CsvValidator extends Report implements ImportValidator {

	private final Vertx vertx;
	private String structureId;
	private final ColumnsMapper columnsMapper;
	private final MappingFinder mappingFinder;
	private boolean findUsersEnabled = true;
	private final Map<String, String> classesNamesMapping = new HashMap<>();
	public static final Map<String, Validator> profiles;

	static {
		Map<String, Validator> p = new HashMap<>();
		p.put("Personnel", new Validator("dictionary/schema/Personnel.json", true));
		p.put("Teacher", new Validator("dictionary/schema/Personnel.json", true));
		p.put("Student", new Validator("dictionary/schema/Student.json", true));
		p.put("Relative", new Validator("dictionary/schema/User.json", true));
		p.put("Guest", new Validator("dictionary/schema/User.json", true));
		profiles = Collections.unmodifiableMap(p);
	}

	public CsvValidator(Vertx vertx, String acceptLanguage, JsonObject additionnalsMappings) {
		super(acceptLanguage);
		this.columnsMapper = new ColumnsMapper(additionnalsMappings);
		this.mappingFinder = new MappingFinder(vertx);
		this.vertx = vertx;
	}

	@Override
	public void validate(final String p, final JsonObject association, final Handler<JsonObject> handler) {
		vertx.fileSystem().readDir(p, new Handler<AsyncResult<String[]>>() {
			@Override
			public void handle(AsyncResult<String[]> event) {
				if (event.succeeded() && event.result().length == 1) {
					final String path = event.result()[0];
					String[] s = path.replaceAll("/$", "").substring(path.lastIndexOf("/") + 1).split("_")[0].split("@");
					if (s.length == 2) {
						structureId = s[1];
					}
					vertx.fileSystem().readDir(path, new Handler<AsyncResult<String[]>>() {
						@Override
						public void handle(final AsyncResult<String[]> event) {
							final String[] importFiles = event.result();
							Arrays.sort(importFiles, Collections.reverseOrder());
							if (event.succeeded() && importFiles.length > 0) {
								final VoidHandler[] handlers = new VoidHandler[importFiles.length + 1];
								handlers[handlers.length -1] = new VoidHandler() {
									@Override
									protected void handle() {
										handler.handle(result);
									}
								};
								for (int i = importFiles.length - 1; i >= 0; i--) {
									final int j = i;
									handlers[i] = new VoidHandler() {
										@Override
										protected void handle() {
											final String file = importFiles[j];
											log.info("Validating file : " + file);
											findUsersEnabled = true;
											final String profile = file.substring(path.length() + 1).replaceFirst(".csv", "");
											CSVUtil.getCharset(vertx, file, new Handler<String>(){

												@Override
												public void handle(final String charset) {
													if (profiles.containsKey(profile)) {
														log.info("Charset : " + charset);
														if( association == null ) {
															checkFile(file, profile, charset, null, new Handler<JsonObject>() {
																@Override
																public void handle(JsonObject event) {
																	handlers[j + 1].handle(null);
																}
															});
														} else {
															//mappingFields(file, profile, charset, association, new Handler<JsonObject>() {
															checkFile(file, profile, charset, association, new Handler<JsonObject>() {
																@Override
																public void handle(JsonObject event) {
																	handlers[j + 1].handle(null);
																}
															});
														}
													} else {
														addError("unknown.profile");
														handler.handle(result);
													}
												}
											});
										}
									};
								}
								handlers[0].handle(null);
							} else {
								addError("error.list.files");
								handler.handle(result);
							}
						}
					});
				} else {
					addError("error.list.files");
					handler.handle(result);
				}
			}
		});
	}

	private void checkFile(final String path, final String profile, final String charset, final JsonObject association, final Handler<JsonObject> handler) {
		CSV csvParser = CSV
				.ignoreLeadingWhiteSpace()
				.separator(';')
				.skipLines(0)
				.charset(charset)
				.create();
		final List<String> columns = new ArrayList<>();
		final AtomicInteger filterExternalId = new AtomicInteger(-1);
		final JsonArray externalIds = new JsonArray();
		csvParser.read(path, new CSVReadProc() {
			@Override
			public void procRow(int i, String... strings) {
				if (i == 0) {
					JsonArray invalidColumns;
					if( association != null ) { // mapping
						invalidColumns = columnsMapper.getColumsAssociations(strings, columns, profile, association);
						if( invalidColumns != null && invalidColumns.size() == 1) {
							Object obj = invalidColumns.get(0);
							if (obj instanceof JsonObject) {
								JsonObject jobj = (JsonObject) obj;
								String sError = jobj.getString("error");
								if (sError != null) {
									addError(profile, sError);
									handler.handle(result);
								}
							}
						}
					} else { // normal
						invalidColumns = columnsMapper.getColumsNames(strings, columns);
					}
					if( invalidColumns != null && invalidColumns.size() == 1 ) {
						if( association == null) { // shouldn't be done if mapping
							parseErrors("invalid.column", invalidColumns, profile, handler);
						}
					} else if (columns.contains("externalId")) {
						if( association != null ) { // mapping
							// find index of externalId in association
							for (String field : association.getFieldNames()) {
								if( association.getString(field).equals("externalId")){
									filterExternalId.set(Integer.parseInt(field));
									break;
								}
							}
						} else {// normal
							int j = 0;
							for (String c : columns) {
								if ("externalId".equals(c)) {
									filterExternalId.set(j);
								}
								j++;
							}
						}
					} else if (structureId != null && !structureId.trim().isEmpty()) {
						findUsersEnabled = false;
						findUsers(path, profile, columns, charset, handler);
					} else {
						validateFile(path, profile, columns, null, charset, association, handler);
					}
				} else if (filterExternalId.get() >= 0) {
					if (strings[filterExternalId.get()] != null && !strings[filterExternalId.get()].isEmpty()) {
						externalIds.addString(strings[filterExternalId.get()]);
					} else if (findUsersEnabled) { // TODO add check to empty lines
						findUsersEnabled = false;
						final int eii = filterExternalId.get();
						filterExternalId.set(-1);
						findUsers(path, profile, columns, eii, charset, handler);
					}
				}
			}
		});
		if (filterExternalId.get() >= 0) {
			filterExternalIdExists(externalIds, new Handler<JsonArray>() {
				@Override
				public void handle(JsonArray externalIdsExists) {
					if (externalIdsExists != null) {
						validateFile(path, profile, columns, externalIdsExists, charset, association, handler);
					} else {
						addError(profile, "error.find.externalIds");
						handler.handle(result);
					}
				}
			});
		}
	}

	private void findUsers(final String path, final String profile, List<String> columns, String charset, final Handler<JsonObject> handler) {
		findUsers(path, profile, columns, -1, charset, handler);
	}

	private void findUsers(final String path, final String profile, List<String> columns, int eii, final String charset, final Handler<JsonObject> handler) {
		mappingFinder.findExternalIds(structureId, path, profile, columns, eii, charset, new Handler<JsonArray>() {
			@Override
			public void handle(JsonArray errors) {
				if (errors.size() > 0) {
					for (Object o: errors) {
						if (!(o instanceof JsonObject)) continue;
						JsonObject j = (JsonObject) o;
						JsonArray p = j.getArray("params");
						log.info(j.encode());
						if (p != null && p.size() > 0) {
							addErrorByFile(profile, j.getString("key"), p.encode().substring(1, p.encode().length() - 1)
									.replaceAll("\"", "").split(","));
						} else if (j.getString("key") != null) {
							addError(profile, j.getString("key"));
						} else {
							addError(profile, "mapping.unknown.error");
						}
					}
					handler.handle(result);
				} else {
					//validateFile(path, profile, columns, null, handler);
					checkFile(path, profile, charset, null, handler);
				}
			}
		});
	}

	private void filterExternalIdExists(JsonArray externalIds, final Handler<JsonArray> handler) {
		String query = "MATCH (u:User) where u.externalId in {externalIds} return collect(u.externalId) as ids";
		TransactionManager.getNeo4jHelper().execute(query, new JsonObject().putArray("externalIds", externalIds), new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				JsonArray result = event.body().getArray("result");
				if ("ok".equals(event.body().getString("status")) && result.size() == 1) {
					handler.handle(result.<JsonObject>get(0).getArray("ids"));
				} else {
					handler.handle(null);
				}
			}
		});
	}

	private void parseErrors(String key, JsonArray invalidColumns, String profile, final Handler<JsonObject> handler) {
		for (Object o : invalidColumns) {
			addErrorByFile(profile, key, (String) o);
		}
		handler.handle(result);
	}

	private void validateFile(final String path, final String profile, final List<String> columns, final JsonArray existExternalId, final String charset, final JsonObject association, final Handler<JsonObject> handler) {
		// if association == null, then it is no mapping
		List<Integer> columnsMappingIndex = null; // list of indexes from the file that are mapped
		final Integer nextMappingIndex = 0;
		if( association != null ){
			columnsMappingIndex = new ArrayList<>();
			int fieldIndex = 0;
			for (String field : association.getFieldNames()) {
				if(! "profile".equals(field)) {
					columnsMappingIndex.add(fieldIndex, Integer.parseInt(field));
				}
				fieldIndex++;
			}
		}
		final Validator validator = profiles.get(profile);
		final List<Integer> finalColumnsMappingIndex = columnsMappingIndex;
		getStructure(path, new Handler<Structure>() {
			@Override
			public void handle(final Structure structure) {

				if (structure == null) {
					addError(profile, "invalid.structure");
					handler.handle(result);
					return;
				}
				CSV csvParser = CSV
						.ignoreLeadingWhiteSpace()
						.separator(';')
						.skipLines(1)
						.charset(charset)
						.create();

				int[] nextMappingIndexTmp = new int[1];
				if( association != null ) {
					nextMappingIndexTmp[0] = finalColumnsMappingIndex.get(0);
				}

				final int[] nextMappingIndex = nextMappingIndexTmp;
				csvParser.read(path, new CSVReadProc() {
					@Override
					// reading the lines
					public void procRow(int i, String... strings) {
						final JsonArray classesNames = new JsonArray();
						JsonObject user = new JsonObject();
						user.putArray("structures", new JsonArray().add(structure.getExternalId()));
						user.putArray("profiles", new JsonArray().add(profile));
						List<String[]> classes = new ArrayList<>();
						// reading the columns
						int nextIndexColumns = -1;
						for (int j = 0; j < strings.length; j++) {
							boolean doNotRead = false; // if mapping and if column is not mapped
							if(association != null ) {
								//get next index to be read
								if( nextIndexColumns + 1 >= finalColumnsMappingIndex.size()){ // we have already reached all the columns we wanted
									doNotRead = true;
								} else if( j != finalColumnsMappingIndex.get(nextIndexColumns+1)) { // that column (j) has not been mapped by user
									doNotRead = true;
								} else {
									nextIndexColumns++;
									if( association != null ) {
										nextMappingIndex[0]++;
									}
								}
							}
							if( !doNotRead ) {
								String ctmp;
								if( association == null ) {
									ctmp = columns.get(j);
								} else {
									// mapping
									ctmp = columns.get(nextIndexColumns);
								}
								final String c = ctmp;
								final String v = strings[j].trim();
								if (v.isEmpty()) continue;
								switch (validator.getType(c)) {
									case "string":
										if ("birthDate".equals(c)) {
											Matcher m = frenchDatePatter.matcher(v);
											if (m.find()) {
												user.putString(c, m.group(3) + "-" + m.group(2) + "-" + m.group(1));
											} else {
												user.putString(c, v);
											}
										} else {
											user.putString(c, v);
										}
										break;
									case "array-string":
										JsonArray a = user.getArray(c);
										if (a == null) {
											a = new JsonArray();
											user.putArray(c, a);
										}
										if (("classes".equals(c) || "subjectTaught".equals(c) || "functions".equals(c)) &&
												!v.startsWith(structure.getExternalId() + "$")) {
											a.add(structure.getExternalId() + "$" + v);
										} else {
											a.add(v);
										}
										break;
									case "boolean":
										user.putBoolean(c, "true".equals(v.toLowerCase()));
										break;
									default:
										Object o = user.getValue(c);
										final String v2;
										if ("childClasses".equals(c) && !v.startsWith(structure.getExternalId() + "$")) {
											v2 = structure.getExternalId() + "$" + v;
										} else {
											v2 = v;
										}
										if (o != null) {
											if (o instanceof JsonArray) {
												((JsonArray) o).add(v2);
											} else {
												JsonArray array = new JsonArray();
												array.add(o).add(v2);
												user.putArray(c, array);
											}
										} else {
											user.putString(c, v2);
										}
								} // end switch
								if ("classes".equals(c)) {
									String eId = structure.getExternalId() + '$' + v;
									String[] classId = new String[2];
									classId[0] = structure.getExternalId();
									classId[1] = eId;
									classes.add(classId);
									classesNames.addString(v);
								}
							} // end if doNotRead
						} // end read columns
						String ca;
						long seed;
						JsonArray classesA;
						Object co = user.getValue("classes");
						if (co != null && co instanceof JsonArray) {
							classesA = (JsonArray) co;
						} else if (co instanceof String) {
							classesA = new JsonArray().add(co);
						} else {
							classesA = null;
						}
						if ("Student".equals(profile) && classesA != null && classesA.size() == 1) {
							seed = CsvFeeder.DEFAULT_STUDENT_SEED;
							ca = classesA.get(0);
						} else {
							ca = String.valueOf(i);
							seed = System.currentTimeMillis();
						}
						final State state;
						final String externalId = user.getString("externalId");
						if (externalId == null || externalId.trim().isEmpty()) {
							generateUserExternalId(user, ca, structure, seed);
							state = State.NEW;
						} else {
							if (existExternalId.contains(externalId)) {
								state = State.UPDATED;
							} else {
								state = State.NEW;
							}
						}
						switch (profile) {
							case "Relative":
								JsonArray linkStudents = new JsonArray();
								user.putArray("linkStudents", linkStudents);
								for (String attr : user.getFieldNames()) {
									if ("childExternalId".equals(attr)) {
										Object o = user.getValue(attr);
										if (o instanceof JsonArray) {
											for (Object c : (JsonArray) o) {
												linkStudents.add(c);
											}
										} else {
											linkStudents.add(o);
										}
									} else if ("childLastName".equals(attr)) {
										Object childLastName = user.getValue(attr);
										Object childFirstName = user.getValue("childFirstName");
										Object childClasses = user.getValue("childClasses");
										if (childLastName instanceof JsonArray && childFirstName instanceof JsonArray &&
												childClasses instanceof JsonArray &&
												((JsonArray) childClasses).size() == ((JsonArray) childLastName).size() &&
												((JsonArray) childFirstName).size() == ((JsonArray) childLastName).size()) {
											for (int j = 0; j < ((JsonArray) childLastName).size(); j++) {
												String mapping = structure.getExternalId() +
														((JsonArray) childLastName).<String>get(i).trim() +
														((JsonArray) childFirstName).<String>get(i).trim() +
														((JsonArray) childClasses).<String>get(i).trim() +
														CsvFeeder.DEFAULT_STUDENT_SEED;
												relativeStudentMapping(linkStudents, mapping);
											}
										} else if (childLastName instanceof String && childFirstName instanceof String &&
												childClasses instanceof String) {
											String mapping = structure.getExternalId() +
													childLastName.toString().trim() +
													childFirstName.toString().trim() +
													childClasses.toString().trim() +
													CsvFeeder.DEFAULT_STUDENT_SEED;
											relativeStudentMapping(linkStudents, mapping);
										} else {
											addError(profile, "invalid.child.mapping");
											handler.handle(result);
											return;
										}
									}
								}
								for (Object o : linkStudents) {
									if (!(o instanceof String)) continue;
									if (classesNamesMapping.get(o) != null) {
										classesNames.addString(classesNamesMapping.get(o));
									}
								}
								break;
						}
						String error = validator.validate(user, acceptLanguage);
						if (error != null) {
							log.warn(error);
							addError(profile, error);
						} else {
							final String classesStr = Joiner.on(", ").join(classesNames);
							classesNamesMapping.put(user.getString("externalId"), classesStr);
							addUser(profile, user.putString("state", translate(state.name()))
									.putString("translatedProfile", translate(profile))
									.putString("classesStr", classesStr));
						}
					}

					private void relativeStudentMapping(JsonArray linkStudents, String mapping) {
						if (mapping.trim().isEmpty()) return;
						try {
							linkStudents.add(Hash.sha1(mapping.getBytes("UTF-8")));
						} catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
							log.error(e.getMessage(), e);
						}
					}

				});
				handler.handle(result);
			}
		});

	}

	private void getStructure(final String path, final Handler<Structure> handler) {
		String query = "MATCH (s:Structure {externalId:{id}})" +
				"return s.id as id, s.externalId as externalId, s.UAI as UAI, s.name as name";
		TransactionManager.getNeo4jHelper().execute(query, new JsonObject().putString("id", structureId), new Handler<Message<JsonObject>>() {
			@Override
			public void handle(Message<JsonObject> event) {
				JsonArray result = event.body().getArray("result");
				if ("ok".equals(event.body().getString("status")) && result != null && result.size() == 1) {
					handler.handle(new Structure(result.<JsonObject>get(0)));
				} else {
					try {
						handler.handle(new Structure(CSVUtil.getStructure(path)));
					} catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
						handler.handle(null);
					}
				}
			}
		});
	}

}
