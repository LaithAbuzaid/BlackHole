@SuppressWarnings("module")
module com.mycompany.mavenproject1 {
    requires javafx.controls;
    requires org.lwjgl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires java.management;
    requires jdk.management;

    exports com.mycompany.mavenproject1;
    exports com.mycompany.mavenproject1.core;
    exports com.mycompany.mavenproject1.graphics;
    exports com.mycompany.mavenproject1.telemetry;
    exports com.mycompany.mavenproject1.ui;
}
