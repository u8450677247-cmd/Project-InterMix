plugins { `java-library` }
java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }
