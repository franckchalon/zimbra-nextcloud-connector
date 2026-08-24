/*
 * Zimbra Classic UI shell for the Nextcloud connector.
 * Copyright (c) 2026 Franck Chalon
 * SPDX-License-Identifier: BSD-3-Clause
 */

function fr_franckchalon_nextcloud_classic_HandlerObject() {
	this._cloudAppName = null;
	this._chatAppName = null;
	this._appRoots = { files: null, chat: null };
	this._pickerRoot = null;
	this._pickerDialog = null;
	this._runtimeLoading = false;
	this._runtimeCallbacks = [];
	this._runtimeError = null;
	this._quickChatOpen = false;
}

fr_franckchalon_nextcloud_classic_HandlerObject.prototype = new ZmZimletBase();
fr_franckchalon_nextcloud_classic_HandlerObject.prototype.constructor = fr_franckchalon_nextcloud_classic_HandlerObject;

fr_franckchalon_nextcloud_classic_HandlerObject.ZIMLET_ID = 'fr_franckchalon_nextcloud_classic';
fr_franckchalon_nextcloud_classic_HandlerObject.APP_ROOT_ID = 'fr-franckchalon-nextcloud-classic-root';
fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_APP_ROOT_ID = 'fr-franckchalon-nextcloud-classic-chat-app-root';
fr_franckchalon_nextcloud_classic_HandlerObject.PICKER_ROOT_ID = 'fr-franckchalon-nextcloud-classic-picker';
fr_franckchalon_nextcloud_classic_HandlerObject.COMPOSE_OPERATION = 'frFranckChalonNextcloudAttach';
fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_LAUNCHER_ID = 'fr-nextcloud-classic-chat-launcher';
fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_PANEL_ID = 'fr-nextcloud-classic-chat-panel';
fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_ROOT_ID = 'fr-nextcloud-classic-chat-root';

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._message = function (key, fallback) {
	try {
		var value = this.getMessage(key);
		if (value && value.indexOf('???') !== 0) return value;
	} catch (ignore) {}
	return fallback;
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._runtimeOrNull = function () {
	var runtime = window.FranckChalonNextcloudClassicApp;
	return runtime && typeof runtime.mount === 'function' ? runtime : null;
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._runtime = function () {
	var runtime = this._runtimeOrNull();
	if (runtime) return runtime;
	throw new Error(this._message('runtimeUnavailable', 'The Nextcloud Classic UI runtime is unavailable.'));
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._finishRuntimeLoad = function (error) {
	var callbacks = this._runtimeCallbacks.slice(0);
	var runtime = error ? null : this._runtimeOrNull();
	if (!error && !runtime) {
		error = new Error(this._message('runtimeUnavailable', 'The Nextcloud Classic UI runtime is unavailable.'));
	}
	this._runtimeCallbacks = [];
	this._runtimeLoading = false;
	this._runtimeError = error || null;
	for (var index = 0; index < callbacks.length; index += 1) {
		try { callbacks[index](this._runtimeError, runtime); } catch (callbackError) {
			if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic]', callbackError);
		}
	}
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._loadRuntime = function (callback) {
	var runtime = this._runtimeOrNull();
	if (runtime) {
		callback(null, runtime);
		return;
	}
	// A previous network/cache failure must not permanently disable the Zimlet.
	this._runtimeError = null;
	this._runtimeCallbacks.push(callback);
	if (this._runtimeLoading) return;
	this._runtimeLoading = true;

	var self = this;
	var script = document.createElement('script');
	script.type = 'text/javascript';
	script.async = true;
	script.src = this.getResource(fr_franckchalon_nextcloud_classic_HandlerObject.ZIMLET_ID + '_app.js');
	script.onload = function () { self._finishRuntimeLoad(null); };
	script.onerror = function () {
		self._finishRuntimeLoad(new Error(self._message(
			'runtimeUnavailable',
			'The Nextcloud Classic UI runtime is unavailable.'
		)));
	};
	(document.head || document.getElementsByTagName('head')[0] || document.body).appendChild(script);
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._language = function () {
	var locale = '';
	try { locale = appCtxt.get(ZmSetting.LOCALE_NAME); } catch (ignore) {}
	try { if (!locale && window.AjxEnv) locale = AjxEnv.DEFAULT_LOCALE; } catch (ignore2) {}
	return String(locale || 'fr').replace('_', '-').slice(0, 12);
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._workspaceScope = function () {
	var account = null;
	try { account = appCtxt.getActiveAccount(); } catch (ignore) {}
	try { return String(account && (account.id || account.name) || appCtxt.getUsername() || 'classic'); } catch (ignore2) {}
	return 'classic';
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype.init = function () {
	this._cloudAppName = this.createApp(
		this._message('cloudLabel', 'Cloud'),
		'frNextcloudCloudIcon',
		this._message('cloudTooltip', 'Open Nextcloud files')
	);
	this._chatAppName = this.createApp(
		this._message('chatLabel', 'Chat'),
		'frNextcloudChatIcon',
		this._message('chatTooltip', 'Open Nextcloud Talk')
	);
	var self = this;
	var defer = window.setTimeout || function (callback) { callback(); };
	defer(function () { self._installChatLauncher(); }, 0);
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype.onSelectApp = function (appName) {
	if (appName === this._cloudAppName) this._mountApplication('files');
	else if (appName === this._chatAppName) this._mountApplication('chat');
};

// Keep the standard Zimlet-panel entry useful as a fallback on Classic skins
// that hide application tabs when the top bar is too narrow.
fr_franckchalon_nextcloud_classic_HandlerObject.prototype.singleClicked = function () {
	try {
		var controller = appCtxt.getAppController && appCtxt.getAppController();
		if (controller && typeof controller.activateApp === 'function' && this._cloudAppName) {
			controller.activateApp(this._cloudAppName);
		}
	} catch (ignore) {}
	this._mountApplication('files');
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype.doubleClicked = function () {
	this.singleClicked();
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._mountApplication = function (initialView) {
	var view = initialView === 'chat' ? 'chat' : 'files';
	var appName = view === 'chat' ? this._chatAppName : this._cloudAppName;
	var rootId = view === 'chat'
		? fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_APP_ROOT_ID
		: fr_franckchalon_nextcloud_classic_HandlerObject.APP_ROOT_ID;
	var app = appCtxt.getApp(appName);
	if (!app) return;

	var existingRuntime = this._runtimeOrNull();
	try {
		if (existingRuntime && this._appRoots[view]) existingRuntime.unmount(this._appRoots[view]);
	} catch (ignore) {}

	app.setContent('<div id="' + rootId + '" class="frNextcloudClassicRoot"></div>');
	this._appRoots[view] = document.getElementById(rootId);
	if (!this._appRoots[view]) return;

	var self = this;
	var root = this._appRoots[view];
	root.textContent = this._message('loadingLabel', 'Loading Nextcloud…');
	this._loadRuntime(function (error, runtime) {
		if (self._appRoots[view] !== root) return;
		if (error) {
			root.innerHTML = '<div class="frNextcloudClassicError"></div>';
			root.firstChild.textContent = error && error.message ? error.message : String(error);
			if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic]', error);
			return;
		}
		try {
			// Remove the provisional text node before Preact owns the container.
			// Otherwise some Classic skins keep "Loading Nextcloud…" beside the app.
			root.textContent = '';
			runtime.mount(root, {
				workspaceScope: this._workspaceScope(),
				language: this._language(),
				initialView: view
			});
		} catch (mountError) {
			root.innerHTML = '<div class="frNextcloudClassicError"></div>';
			root.firstChild.textContent = mountError && mountError.message ? mountError.message : String(mountError);
			if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic]', mountError);
		}
	}.bind(this));
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype.appActive = function (appName, active) {
	if (appName !== this._cloudAppName && appName !== this._chatAppName) return;
	if (this._chatLauncher) {
		this._chatLauncher.style.display = active && appName === this._chatAppName ? 'none' : '';
	}
	if (active && appName === this._chatAppName) this._closeQuickChat();
	var view = appName === this._chatAppName ? 'chat' : 'files';
	if (!active || !this._appRoots[view]) return;
	try {
		window.dispatchEvent(new CustomEvent('com-nextcloud-connector-cloud-view', {
			detail: { scope: this._workspaceScope(), view: view }
		}));
	} catch (ignore) {}
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._installChatLauncher = function () {
	if (!document || !document.body || typeof document.createElement !== 'function') return;
	var existing = document.getElementById(fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_LAUNCHER_ID);
	if (existing) {
		this._chatLauncher = existing;
		this._chatPanel = document.getElementById(fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_PANEL_ID);
		this._chatRoot = document.getElementById(fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_ROOT_ID);
		this._chatUnreadBadge = typeof existing.querySelector === 'function'
			? existing.querySelector('.frNextcloudClassicUnread') : null;
		return;
	}

	var self = this;
	var launcher = document.createElement('button');
	launcher.id = fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_LAUNCHER_ID;
	launcher.type = 'button';
	launcher.title = this._message('quickChatTooltip', 'Open Nextcloud Talk quick chat');
	launcher.setAttribute('aria-controls', fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_PANEL_ID);
	launcher.setAttribute('aria-expanded', 'false');
	var launcherIcon = document.createElement('span');
	launcherIcon.setAttribute('aria-hidden', 'true');
	launcherIcon.textContent = '💬';
	var launcherLabel = document.createElement('span');
	launcherLabel.textContent = this._message('chatLabel', 'Chat');
	var unreadBadge = document.createElement('span');
	unreadBadge.className = 'frNextcloudClassicUnread';
	unreadBadge.style.display = 'none';
	launcher.appendChild(launcherIcon);
	launcher.appendChild(document.createTextNode(' '));
	launcher.appendChild(launcherLabel);
	launcher.appendChild(unreadBadge);
	launcher.addEventListener('click', function () { self._toggleQuickChat(); });

	var panel = document.createElement('section');
	panel.id = fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_PANEL_ID;
	panel.setAttribute('role', 'dialog');
	panel.setAttribute('aria-label', this._message('quickChatTooltip', 'Open Nextcloud Talk quick chat'));
	var panelHeader = document.createElement('div');
	panelHeader.className = 'frNextcloudClassicChatHeader';
	var panelTitle = document.createElement('strong');
	panelTitle.textContent = '💬 ' + this._message('chatLabel', 'Chat');
	var fullButton = document.createElement('button');
	fullButton.type = 'button';
	fullButton.title = this._message('fullChatTooltip', 'Open full Talk workspace');
	fullButton.textContent = '↗';
	fullButton.addEventListener('click', function () { self._openFullChat(); });
	var closeButton = document.createElement('button');
	closeButton.type = 'button';
	closeButton.title = this._message('closeLabel', 'Close');
	closeButton.textContent = '×';
	closeButton.addEventListener('click', function () { self._closeQuickChat(); });
	var chatRoot = document.createElement('div');
	chatRoot.id = fr_franckchalon_nextcloud_classic_HandlerObject.CHAT_ROOT_ID;
	chatRoot.className = 'frNextcloudClassicChatContent';
	panelHeader.appendChild(panelTitle);
	panelHeader.appendChild(fullButton);
	panelHeader.appendChild(closeButton);
	panel.appendChild(panelHeader);
	panel.appendChild(chatRoot);

	document.body.appendChild(panel);
	document.body.appendChild(launcher);
	this._chatLauncher = launcher;
	this._chatPanel = panel;
	this._chatRoot = chatRoot;
	this._chatUnreadBadge = unreadBadge;
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._toggleQuickChat = function () {
	if (this._quickChatOpen) this._closeQuickChat();
	else this._openQuickChat();
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._openQuickChat = function () {
	if (!this._chatPanel || !this._chatRoot || this._quickChatOpen) return;
	var self = this;
	this._quickChatOpen = true;
	this._chatPanel.className = 'frNextcloudClassicChatOpen';
	this._chatLauncher.setAttribute('aria-expanded', 'true');
	this._chatRoot.textContent = this._message('loadingLabel', 'Loading Nextcloud…');
	this._loadRuntime(function (error, runtime) {
		if (!self._quickChatOpen) return;
		if (error) {
			self._quickChatMounted = false;
			self._chatRoot.textContent = error && error.message ? error.message : String(error);
			if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic]', error);
			return;
		}
		try {
			if (typeof runtime.mountChat !== 'function') {
				throw new Error(this._message('runtimeUnavailable', 'The Nextcloud Classic UI runtime is unavailable.'));
			}
			self._chatRoot.textContent = '';
			runtime.mountChat(self._chatRoot, {
				workspaceScope: self._workspaceScope(),
				language: self._language(),
				onClose: function () { self._closeQuickChat(); },
				onOverview: function (overview) { self._updateChatUnread(overview && overview.unread); }
			});
			self._quickChatMounted = true;
		} catch (mountError) {
			self._quickChatMounted = false;
			self._chatRoot.textContent = mountError && mountError.message ? mountError.message : String(mountError);
			if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic]', mountError);
		}
	}.bind(this));
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._closeQuickChat = function () {
	if (this._quickChatMounted && this._chatRoot) {
		try { this._runtime().unmount(this._chatRoot); } catch (ignore) {}
	}
	this._quickChatOpen = false;
	this._quickChatMounted = false;
	if (this._chatPanel) this._chatPanel.className = '';
	if (this._chatLauncher) this._chatLauncher.setAttribute('aria-expanded', 'false');
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._updateChatUnread = function (unread) {
	var badge = this._chatUnreadBadge;
	if (!badge && this._chatLauncher && typeof this._chatLauncher.querySelector === 'function') {
		badge = this._chatLauncher.querySelector('.frNextcloudClassicUnread');
	}
	if (!badge) return;
	var count = Math.max(0, Number(unread) || 0);
	badge.textContent = count > 99 ? '99+' : String(count);
	badge.style.display = count > 0 ? '' : 'none';
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._openFullChat = function () {
	var self = this;
	this._closeQuickChat();
	try {
		var controller = appCtxt.getAppController && appCtxt.getAppController();
		if (controller && typeof controller.activateApp === 'function') controller.activateApp(this._chatAppName);
	} catch (ignore) {}
	var defer = window.setTimeout || function (callback) { callback(); };
	defer(function () { self._mountApplication('chat'); }, 0);
};

// ZmComposeView calls the documented Zimlet hook initializeAttachPopup after
// creating the native "Attach" menu. Adding Cloud here keeps it beside the
// other attachment sources and avoids a theme-dependent toolbar operation.
fr_franckchalon_nextcloud_classic_HandlerObject.prototype.initializeAttachPopup = function (menu, composeView) {
	if (!menu || !composeView || menu._frNextcloudCloudItem) return;
	var menuItemApi = window.DwtMenuItem || (typeof DwtMenuItem !== 'undefined' ? DwtMenuItem : null);
	if (!menuItemApi) return;

	var item = null;
	try {
		if (typeof menuItemApi.create === 'function') {
			item = menuItemApi.create({ parent: menu, text: this._message('attachButton', 'Cloud') });
		} else {
			try { item = new menuItemApi({ parent: menu }); }
			catch (constructorError) { item = new menuItemApi(menu); }
			if (item && typeof item.setText === 'function') item.setText(this._message('attachButton', 'Cloud'));
		}
	} catch (error) {
		if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic] attachment menu', error);
		return;
	}
	if (!item || typeof item.addSelectionListener !== 'function') return;

	item.value = fr_franckchalon_nextcloud_classic_HandlerObject.COMPOSE_OPERATION;
	if (typeof item.setImage === 'function') item.setImage('frNextcloudCloudIcon');
	if (typeof item.setToolTipContent === 'function') {
		item.setToolTipContent(this._message('attachTooltip', 'Attach files or insert read-only links from Nextcloud'));
	}
	item.addSelectionListener(new AjxListener(this, this._openComposePicker, [composeView]));
	menu._frNextcloudCloudItem = item;
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._composeView = function (controller) {
	if (controller && typeof controller._submitMyComputerAttachments === 'function') return controller;
	var view = null;
	try { view = controller && typeof controller.getCurrentView === 'function' ? controller.getCurrentView() : null; } catch (ignore) {}
	try { if (!view && controller && typeof controller.getView === 'function') view = controller.getView(); } catch (ignore2) {}
	try { if (!view) view = appCtxt.getCurrentView(); } catch (ignore3) {}
	return view;
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._pickerDimensions = function () {
	var documentElement = document.documentElement || {};
	var body = document.body || {};
	var viewportWidth = Number(window.innerWidth || documentElement.clientWidth || body.clientWidth || 1024);
	var viewportHeight = Number(window.innerHeight || documentElement.clientHeight || body.clientHeight || 768);
	var availableWidth = Math.max(320, viewportWidth - 48);
	var availableHeight = Math.max(280, viewportHeight - 150);
	return {
		width: Math.min(860, availableWidth),
		height: Math.min(620, availableHeight)
	};
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._openComposePicker = function (controller) {
	var self = this;
	var composeView = this._composeView(controller);
	if (!composeView) return;

	this._closeComposePicker();
	var dimensions = this._pickerDimensions();
	var pickerView = new DwtComposite(this.getShell());
	pickerView.setSize(dimensions.width, dimensions.height);
	var pickerViewElement = pickerView.getHtmlElement();
	pickerViewElement.className += (pickerViewElement.className ? ' ' : '') + 'frNextcloudClassicPickerView';
	pickerViewElement.style.width = dimensions.width + 'px';
	pickerViewElement.style.height = dimensions.height + 'px';
	pickerViewElement.style.maxWidth = '100%';
	pickerViewElement.style.maxHeight = dimensions.height + 'px';
	pickerViewElement.style.overflow = 'hidden';
	pickerViewElement.innerHTML = '<div id="' + fr_franckchalon_nextcloud_classic_HandlerObject.PICKER_ROOT_ID + '" class="frNextcloudClassicPicker" style="height:' + dimensions.height + 'px"></div>';
	this._pickerDialog = new ZmDialog({
		title: this._message('pickerTitle', 'Nextcloud — attach or share'),
		parent: this.getShell(),
		view: pickerView,
		standardButtons: [],
		disposeOnPopDown: true
	});
	this._pickerDialog.popup();
	this._pickerRoot = document.getElementById(fr_franckchalon_nextcloud_classic_HandlerObject.PICKER_ROOT_ID);
	if (!this._pickerRoot) return;

	var pickerRoot = this._pickerRoot;
	pickerRoot.textContent = this._message('loadingLabel', 'Loading Nextcloud…');
	this._loadRuntime(function (error, runtime) {
		if (self._pickerRoot !== pickerRoot) return;
		if (error) {
			pickerRoot.textContent = error && error.message ? error.message : String(error);
			if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic]', error);
			return;
		}
		try {
			pickerRoot.textContent = '';
			runtime.mountPicker(pickerRoot, {
				language: this._language(),
				onAttachFiles: function (files) { return self._attachToCompose(composeView, files); },
				onInsertLinks: function (content) { return self._insertLinksIntoCompose(composeView, content); },
				onClose: function () { self._closeComposePicker(); }
			});
		} catch (mountError) {
			pickerRoot.textContent = mountError && mountError.message ? mountError.message : String(mountError);
			if (window.console && console.error) console.error('[fr_franckchalon_nextcloud_classic]', mountError);
		}
	}.bind(this));
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._attachToCompose = function (composeView, files) {
	if (!composeView || typeof composeView._submitMyComputerAttachments !== 'function') {
		throw new Error(this._message('attachmentUnavailable', 'This Zimbra version does not expose the attachment API.'));
	}
	composeView._submitMyComputerAttachments(files, null, false);
	return true;
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._insertLinksIntoCompose = function (composeView, content) {
	var editor = null;
	try { editor = typeof composeView.getHtmlEditor === 'function' ? composeView.getHtmlEditor() : null; } catch (ignore) {}
	if (!editor || typeof editor.getContent !== 'function' || typeof editor.setContent !== 'function') return false;

	var mode = '';
	try { mode = String(editor.getMode ? editor.getMode() : ''); } catch (ignore2) {}
	var addition = mode === 'text/plain' ? content.text : content.html;
	var separator = mode === 'text/plain' ? '\r\n' : '<br>';
	var current = String(editor.getContent() || '');
	var markers = mode === 'text/plain' ? ['----'] : ['<hr id="', '<div id="', '</body'];
	var inserted = false;
	for (var index = 0; index < markers.length; index += 1) {
		var marker = markers[index];
		var offset = current.indexOf(marker);
		if (offset > 0) {
			current = current.slice(0, offset) + addition + separator + current.slice(offset);
			inserted = true;
			break;
		}
	}
	if (!inserted) current += separator + addition + separator;
	editor.setContent(current);
	return true;
};

fr_franckchalon_nextcloud_classic_HandlerObject.prototype._closeComposePicker = function () {
	try { if (this._pickerRoot) this._runtime().unmount(this._pickerRoot); } catch (ignore) {}
	this._pickerRoot = null;
	try { if (this._pickerDialog) this._pickerDialog.popdown(); } catch (ignore2) {}
	this._pickerDialog = null;
};

var FrFranckChalonNextcloudClassic = fr_franckchalon_nextcloud_classic_HandlerObject;
if (typeof window !== 'undefined') {
	window.fr_franckchalon_nextcloud_classic_HandlerObject = fr_franckchalon_nextcloud_classic_HandlerObject;
	window.FrFranckChalonNextcloudClassic = fr_franckchalon_nextcloud_classic_HandlerObject;
}
