package io.github.isagroup.spaceclient.types;

/**
 * Stable codes of novation validation errors. {@link NovationError#getCode()} keeps the string SPACE
 * sent, so a code added later still arrives; {@link NovationError#getKnownCode()} maps it here.
 */
public enum NovationErrorCode {
    PLAN_NOT_FOUND,
    ADDON_NOT_FOUND,
    ADDON_NOT_AVAILABLE,
    ADDON_DEPENDENCY_MISSING,
    ADDON_EXCLUDED,
    QUANTITY_OUT_OF_RANGE,
    VERSION_NOT_FOUND,
    VERSION_ARCHIVED,
    PRIVATE_PLAN,
    PRIVATE_ADDON;

    /** The constant named {@code code}, or {@code null} for a code this client does not know. */
    public static NovationErrorCode fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (NovationErrorCode value : values()) {
            if (value.name().equals(code)) {
                return value;
            }
        }
        return null;
    }
}
