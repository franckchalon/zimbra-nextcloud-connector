import path from 'path';

export default function configure(config) {
	// Keep the Zimlet CLI entry wrapper. Replacing config.entry bypasses the
	// `zimlet(context => ...)` bootstrap, so shims such as Preact are read before
	// Zimbra provides them and the whole consolidated Zimlet loader fails.
	config.resolve.alias['zimlet-cli-entrypoint'] = path.resolve(process.cwd(), 'src/chat-nav-index.js');
	return config;
}
