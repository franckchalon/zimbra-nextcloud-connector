// The historical auxiliary package is intentionally inert.
//
// Zimbra 10.1.20 can duplicate or misalign independent vertical-menu slots
// when Cloud and Chat are deployed as separate navigation entries. Keeping the
// package ID deployable lets upgrades replace the old bundle cleanly, while all
// Chat access now lives in the stable floating quick-chat panel owned by the
// main Cloud package.
export default function NextcloudChatNavigationZimlet() {
	return { init() {} };
}
