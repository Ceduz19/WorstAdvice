package com.github.ceduz19.worstadvice.api;

/** A group of optional callbacks installed on one method. */
public final class Advice {

    private final EntryAdvice entry;
    private final ExitAdvice exit;
    private final ExceptionAdvice exception;

    private Advice(EntryAdvice entry, ExitAdvice exit, ExceptionAdvice exception) {
        this.entry = entry;
        this.exit = exit;
        this.exception = exception;
    }

    /** @return the entry callback, or {@code null} */
    public EntryAdvice entry() {
        return entry;
    }

    /** @return the normal-exit callback, or {@code null} */
    public ExitAdvice exit() {
        return exit;
    }

    /** @return the exceptional-exit callback, or {@code null} */
    public ExceptionAdvice exception() {
        return exception;
    }

    /** @return a new advice builder */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds one immutable advice callback group. */
    public static final class Builder {

        private EntryAdvice entry;
        private ExitAdvice exit;
        private ExceptionAdvice exception;

        private Builder() {
        }

        /** @param entry callback to invoke on entry @return this builder */
        public Builder onEnter(EntryAdvice entry) {
            this.entry = entry;
            return this;
        }

        /** @param exit callback to invoke after a normal return @return this builder */
        public Builder onExit(ExitAdvice exit) {
            this.exit = exit;
            return this;
        }

        /** @param exception callback to invoke after a thrown value @return this builder */
        public Builder onException(ExceptionAdvice exception) {
            this.exception = exception;
            return this;
        }

        /** @return the configured advice @throws IllegalStateException when no callback was set */
        public Advice build() {
            if (entry == null && exit == null && exception == null)
                throw new IllegalStateException("At least one advice callback is required");

            return new Advice(entry, exit, exception);
        }
    }
}
