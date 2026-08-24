import { createElement, Component } from 'preact';
import { ActionMenuItem, ModalDialog } from '@zimbra-client/components';

import CloudPickerCore from '../cloud-picker';
import { languageFromContext, translate } from '../../i18n';
import { insertComposeContent, resolveComposeBridge } from './compose-bridge';
import style from './style.less';

const MODAL_ID = 'com-nextcloud-connector-cloud-picker';

function ModernCloudPicker({ context, editor, composeBridge, onClose }) {
	const language = languageFromContext(context, 'fr');
	const ParentFile = globalThis.parent && globalThis.parent.File ? globalThis.parent.File : globalThis.File;
	return <ModalDialog
		title={translate(language, 'chooseCloudFiles')}
		onClose={onClose}
		class={style.modalDialog}
		contentClass={style.modalContent}
		innerClass={style.modalInner}
		cancelButton={false}
		footer={false}
	>
		<CloudPickerCore
			language={language}
			fileConstructor={ParentFile}
			onAttachFiles={files => editor.addAttachments(files, true)}
			onInsertLinks={content => insertComposeContent(composeBridge, editor, content.html, content.text)}
			onClose={onClose}
		/>
	</ModalDialog>;
}

export default class CloudAttacher extends Component {
	closePicker = () => {
		const { context } = this.props;
		context.store.dispatch(context.zimletRedux.actions.zimlets.removeModal({ id: MODAL_ID }));
	};

	openPicker = editor => {
		if (!editor || typeof editor.addAttachments !== 'function') return;
		const { context } = this.props;
		const modal = <ModernCloudPicker context={context} editor={editor} composeBridge={this.composeBridge} onClose={this.closePicker} />;
		context.store.dispatch(context.zimletRedux.actions.zimlets.addModal({ id: MODAL_ID, modal }));
	};

	chooseCloudFiles = () => {
		if (typeof this.props.onAttachmentOptionSelection === 'function') {
			this.props.onAttachmentOptionSelection(editor => {
				this.composeBridge = resolveComposeBridge(editor);
				this.openPicker(editor);
			});
		}
	};

	render() {
		const language = languageFromContext(this.props.context, 'fr');
		return <ActionMenuItem icon="cloud" onClick={this.chooseCloudFiles}>{translate(language, 'cloud')}</ActionMenuItem>;
	}
}
