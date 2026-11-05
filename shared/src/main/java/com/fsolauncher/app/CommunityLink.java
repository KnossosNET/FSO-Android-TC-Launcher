package com.fsolauncher.app;

import java.net.URI;
import java.net.URISyntaxException;

/** Developer-configured external community link; icons are optional local drawables. */
public final class CommunityLink {
    public String label, description, url, icon;
    public boolean isWebLink() {
        if (label == null || label.trim().isEmpty() || url == null) return false;
        try {
            URI address = new URI(url);
            return ("https".equalsIgnoreCase(address.getScheme()) || "http".equalsIgnoreCase(address.getScheme()))
                && address.getHost() != null && address.getUserInfo() == null;
        } catch (URISyntaxException e) { return false; }
    }
    public String accessibleLabel() {
        return description == null || description.trim().isEmpty() ? label.trim() : description.trim();
    }
}
