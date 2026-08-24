import { createElement, Component, render } from 'preact';

import App from './components/app';
import Chat from './components/chat';
import CloudPickerCore from './components/cloud-picker';
import { setApiLanguage } from './api';
import { normalizeLanguage, translate } from './i18n';

class ClassicBoundary extends Component {
	state = { failed: false };

	componentDidCatch(error) {
		this.setState({ failed: true });
		if (globalThis.console && typeof globalThis.console.error === 'function') {
			globalThis.console.error('[fr_franckchalon_nextcloud_classic] rendering failed', error);
		}
	}

	render() {
		if (!this.state.failed) return this.props.children;
		return <div role="alert" style="margin:24px;padding:18px;border:1px solid #d9a7a3;border-radius:10px;background:#fff4f3;color:#7b1f19">
			<strong>{translate(this.props.language, 'nextcloudDisplayError')}</strong>
			<div>{translate(this.props.language, 'zimbraStillAvailable')}</div>
		</div>;
	}
}

function prepare(container, language) {
	if (!container || container.nodeType !== 1) throw new Error('A Classic UI container is required');
	const normalized = normalizeLanguage(language || 'fr', 'fr');
	setApiLanguage(normalized);
	return normalized;
}

function mount(container, options = {}) {
	const language = prepare(container, options.language);
	container.setAttribute('data-nextcloud-classic-root', 'app');
	render(<ClassicBoundary language={language}>
		<App
			workspaceScope={options.workspaceScope || 'classic'}
			userLanguage={language}
			initialView={options.initialView === 'chat' ? 'chat' : 'files'}
		/>
	</ClassicBoundary>, container);
	return container;
}

function mountPicker(container, options = {}) {
	const language = prepare(container, options.language);
	container.setAttribute('data-nextcloud-classic-root', 'picker');
	render(<ClassicBoundary language={language}>
		<CloudPickerCore
			language={language}
			fileConstructor={options.fileConstructor || globalThis.File}
			onAttachFiles={options.onAttachFiles}
			onInsertLinks={options.onInsertLinks}
			onClose={options.onClose || (() => unmount(container))}
		/>
	</ClassicBoundary>, container);
	return container;
}

function mountChat(container, options = {}) {
	const language = prepare(container, options.language);
	container.setAttribute('data-nextcloud-classic-root', 'chat');
	render(<ClassicBoundary language={language}>
		<Chat
			workspaceScope={options.workspaceScope || 'classic'}
			userLanguage={language}
			onClose={options.onClose}
			onOverview={options.onOverview}
		/>
	</ClassicBoundary>, container);
	return container;
}

function unmount(container) {
	if (!container || container.nodeType !== 1) return;
	render(null, container);
	container.removeAttribute('data-nextcloud-classic-root');
}

const runtime = { mount, mountChat, mountPicker, unmount, version: '3.2.0-beta.7' };
globalThis.FranckChalonNextcloudClassicApp = runtime;

export default runtime;
