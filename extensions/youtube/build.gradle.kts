dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:shared:protobuf", configuration = "shadowRuntimeElements"))
    compileOnly(project(":extensions:youtube:stub"))
    compileOnly(libs.protobuf.javalite)
    compileOnly(libs.annotation)
}

android {
    defaultConfig {
        minSdk = 26
    }
}
