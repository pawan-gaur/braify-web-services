package com.braify.feature.storage.model;

import java.util.Set;

/**
 * What a {@link StoredFile} is for. Drives its key folder, the upload rules (size / types),
 * whether clients may upload it directly, and whether it may be served without a login.
 *
 * <p>{@code publicRead} files are served through {@code GET /api/public/files/{id}} (a redirect to
 * a fresh pre-signed URL) because they are shown where no Braify login exists — emails, the
 * GrapesJS canvas, {@code <img>} tags. Their ids are random UUIDs, so they can't be enumerated.
 * Every other purpose is only reachable through an access-checked endpoint.
 */
public enum StoragePurpose {

    /** Files library ({@code OrgFile}) — uploaded through /api/files, which applies the org's own rules. */
    LIBRARY_FILE         ("files",             false, false, 0,  Set.of()),
    ESIGN_SOURCE         ("esign",             true,  false, 10, Set.of("application/pdf")),
    ESIGN_SIGNED         ("esign",             false, false, 0,  Set.of()),
    ESIGN_ATTACHMENT     ("esign-attachments", false, false, 10, Set.of()),
    ESIGN_TEMPLATE       ("esign-templates",   false, false, 0,  Set.of()),
    BRANDING_LOGO        ("branding",          true,  true,  2,  ImageTypes.ALL),
    AVATAR               ("avatars",           true,  true,  2,  ImageTypes.ALL),
    TEMPLATE_IMAGE       ("template-assets",   true,  true,  5,  ImageTypes.ALL),
    BULK_EMAIL_ATTACHMENT("bulk-email",        true,  false, 10, Set.of("application/pdf"));

    private final String      folder;
    private final boolean     clientUpload;
    private final boolean     publicRead;
    private final int         maxMb;
    private final Set<String> contentTypes;

    StoragePurpose(String folder, boolean clientUpload, boolean publicRead, int maxMb, Set<String> contentTypes) {
        this.folder       = folder;
        this.clientUpload = clientUpload;
        this.publicRead   = publicRead;
        this.maxMb        = maxMb;
        this.contentTypes = contentTypes;
    }

    /** Key folder under {@code <path>/<orgId>/}. */
    public String folder()            { return folder; }
    /** True when {@code POST /api/storage/upload} accepts this purpose. */
    public boolean clientUpload()     { return clientUpload; }
    /** True when the file may be served without authentication (see class doc). */
    public boolean publicRead()       { return publicRead; }
    /** Max size in MB for client uploads; 0 = no purpose-level limit. */
    public int maxMb()                { return maxMb; }
    /** Allowed content types for client uploads; empty = any. */
    public Set<String> contentTypes() { return contentTypes; }

    private static final class ImageTypes {
        static final Set<String> ALL = Set.of(
                "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml");
    }
}
