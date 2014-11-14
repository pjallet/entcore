/*
 * Copyright © WebServices pour l'Éducation, 2014
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

package org.entcore.test.scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import bootstrap._

object DirectoryAdmlScenario {

  val scn =
  // create group
  exec(http("Create group")
    .post("""/directory/group""")
    .header("Content-Type", "application/json")
    .body(StringBody("""{"name": "Group with rattachment"}"""))
    .check(status.is(401)))

  .exec(http("Create group")
    .post("""/directory/group""")
    .header("Content-Type", "application/json")
    .body(StringBody("""{"name": "Group with rattachment", "structureId":"${schoolId}"}"""))
    .check(status.is(201), jsonPath("$.id").find.saveAs("manual-group-id")))

    .exec(http("update group")
    .put("""/directory/group/${manual-group-id}""")
    .header("Content-Type", "application/json")
    .body(StringBody("""{"name": "Group with rattachment updated"}"""))
    .check(status.is(200), jsonPath("$.id").find.is("${manual-group-id}")))

    // add user to group
    .exec(http("add user to group")
    .post("""/directory/user/group/${teacherId}/${manuel-group-id}""")
    .header("Content-Length", "0")
    .check(status.is(401)))

    .exec(http("add user to group")
    .post("""/directory/user/group/${teacherId}/${manual-group-id}""")
    .header("Content-Length", "0")
    .check(status.is(200)))

    .exec(http("add user to group")
    .post("""/directory/user/group/${studentId}/${manual-group-id}""")
    .header("Content-Length", "0")
    .check(status.is(200)))

//    .exec(http("Remove user from group")
//      .delete("""/directory/user/group/${teacherId}/${manual-group-id}""")
//      .header("Content-Length", "0")
//      .check(status.is(200)))
//
//    .exec(http("Remove group")
//    .delete("""/directory/group/${manual-group-id}""")
//    .check(status.is(204)))

}