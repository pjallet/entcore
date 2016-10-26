﻿// Copyright © WebServices pour l'Éducation, 2014
//
// This file is part of ENT Core. ENT Core is a versatile ENT engine based on the JVM.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation (version 3 of the License).
//
// For the sake of explanation, any module that communicate over native
// Web protocols, such as HTTP, with ENT Core is outside the scope of this
// license and could be license under its own terms. This is merely considered
// normal use of ENT Core, and does not fall under the heading of "covered work".
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
import { model, http } from 'entcore/entcore';
import { _ } from 'entcore/libs/underscore/underscore';

var lastMailsWidget = model.widgets.findWidget('lastMails');

http().get('/conversation/list/INBOX?page=0').done(function (mails) {
    lastMailsWidget.mails = _.where(mails, { unread: true });
    model.widgets.apply();
});