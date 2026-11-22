@SuppressWarnings("module")
module io.github.laithabuzaid.blackhole {
    requires org.lwjgl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires org.lwjgl.openal;
    requires java.management;
    requires jdk.management;

    exports io.github.laithabuzaid.blackhole;
    exports io.github.laithabuzaid.blackhole.audio;
    exports io.github.laithabuzaid.blackhole.core;
    exports io.github.laithabuzaid.blackhole.graphics;
    exports io.github.laithabuzaid.blackhole.math;
    exports io.github.laithabuzaid.blackhole.telemetry;
    exports io.github.laithabuzaid.blackhole.ui;
    exports io.github.laithabuzaid.blackhole.util;
}
