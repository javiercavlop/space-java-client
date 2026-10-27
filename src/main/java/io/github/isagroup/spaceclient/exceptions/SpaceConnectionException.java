package io.github.isagroup.spaceclient.exceptions;

import java.io.IOException;

/**
 * SPACE could not be reached at all: a timeout, a refused connection, DNS. The request never got
 * an answer, so nothing can be said about whether it took effect. The {@link IOException} is the
 * cause.
 */
public class SpaceConnectionException extends SpaceException {
    public SpaceConnectionException(String message, IOException cause) {
        super(message, cause);
    }
}
