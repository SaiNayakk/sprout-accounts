package app.sprout.accounts.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the accounts contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request isn't valid"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in to continue"),
    ACCOUNT_EXISTS(HttpStatus.CONFLICT, "You already have an account"),
    PAN_IN_USE(HttpStatus.CONFLICT, "This PAN already has an account"),
    KYC_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "We couldn't verify you"),
    VPA_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "No such UPI address"),
    NO_ACCOUNT(HttpStatus.NOT_FOUND, "No account yet"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
