package fr.franckchalon.zimbra.nextcloud;

import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.extension.ExtensionDispatcherServlet;
import com.zimbra.cs.extension.ZimbraExtension;

public final class NextcloudConnectorExtension implements ZimbraExtension {
    @Override
    public String getName() {
        return "NextcloudConnectorExtension";
    }

    @Override
    public void init() throws ServiceException {
        try {
            ExtensionDispatcherServlet.register(this, new NextcloudConnectorHandler());
        } catch (Throwable ignored) {
            // Une extension facultative ne doit jamais empêcher mailboxd de démarrer.
        }
    }

    @Override
    public void destroy() {
        try { ExtensionDispatcherServlet.unregister(this); }
        catch (Throwable ignored) {}
    }
}
