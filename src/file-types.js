const OFFICE_EXTENSIONS = new Set([
	'doc', 'docx', 'odt', 'rtf', 'txt', 'html', 'htm',
	'xls', 'xlsx', 'ods', 'csv',
	'ppt', 'pptx', 'odp',
	'pdf'
]);

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'avif']);
const VIDEO_EXTENSIONS = new Set(['mp4', 'webm', 'ogv', 'mov', 'm4v']);
const AUDIO_EXTENSIONS = new Set(['mp3', 'wav', 'ogg', 'oga', 'm4a', 'aac', 'flac']);
const TEXT_EXTENSIONS = new Set(['txt', 'md', 'log', 'json', 'xml', 'csv', 'ini', 'yaml', 'yml']);

export function extensionOf(file) {
	const name = String((file && file.name) || '');
	const dot = name.lastIndexOf('.');
	return dot > -1 ? name.slice(dot + 1).toLowerCase() : '';
}

export function previewKind(file) {
	const mime = String((file && file.mimeType) || '').toLowerCase();
	const ext = extensionOf(file);

	if (mime.startsWith('image/') || IMAGE_EXTENSIONS.has(ext)) return 'image';
	if (mime.startsWith('video/') || VIDEO_EXTENSIONS.has(ext)) return 'video';
	if (mime.startsWith('audio/') || AUDIO_EXTENSIONS.has(ext)) return 'audio';
	if (mime === 'application/pdf' || ext === 'pdf') return 'pdf';
	if (mime.startsWith('text/') || TEXT_EXTENSIONS.has(ext)) return 'text';
	return null;
}

export function canOpenInOnlyOffice(file) {
	return OFFICE_EXTENSIONS.has(extensionOf(file));
}

export function iconFor(file) {
	if (file.isDirectory) return '📁';
	const kind = previewKind(file);
	if (kind === 'image') return '🖼️';
	if (kind === 'video') return '🎬';
	if (kind === 'audio') return '🎵';
	if (kind === 'pdf') return '📕';
	if (canOpenInOnlyOffice(file)) return '📄';
	return '📎';
}
