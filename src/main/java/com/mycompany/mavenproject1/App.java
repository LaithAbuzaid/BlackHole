package com.mycompany.mavenproject1;

import com.mycompany.mavenproject1.core.Application;

/**
 * Application entry point. Rendering and lifecycle implementation lives in
 * {@link Application}.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        new Application().run();
    }
}
