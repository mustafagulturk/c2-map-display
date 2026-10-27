package io.github.mustafagulturk.c2map;

import io.github.mustafagulturk.c2map.ui.C2MapApp;

/**
 * Entry point. Kept separate from the {@code Application} subclass so the program also starts
 * from a plain classpath (fat jar, IDE) without the "JavaFX runtime components are missing" check.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        C2MapApp.main(args);
    }
}
