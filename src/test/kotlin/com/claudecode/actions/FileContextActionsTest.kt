package com.claudecode.actions

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FileContextActionsTest {

    @Nested
    inner class IsSourceFile {
        private val action = GenerateTestsFolderWithClaudeAction()
        private val isSourceFileMethod = action.javaClass.getDeclaredMethod("isSourceFile", String::class.java).apply {
            isAccessible = true
        }

        private fun isSourceFile(name: String): Boolean = isSourceFileMethod.invoke(action, name) as Boolean

        @Test
        fun `java files are source files`() {
            assertTrue(isSourceFile("Main.java"))
        }

        @Test
        fun `kotlin files are source files`() {
            assertTrue(isSourceFile("App.kt"))
        }

        @Test
        fun `kts files are source files`() {
            assertTrue(isSourceFile("build.gradle.kts"))
        }

        @Test
        fun `python files are source files`() {
            assertTrue(isSourceFile("script.py"))
        }

        @Test
        fun `javascript files are source files`() {
            assertTrue(isSourceFile("app.js"))
        }

        @Test
        fun `mjs files are source files`() {
            assertTrue(isSourceFile("module.mjs"))
        }

        @Test
        fun `typescript files are source files`() {
            assertTrue(isSourceFile("app.ts"))
        }

        @Test
        fun `mts files are source files`() {
            assertTrue(isSourceFile("module.mts"))
        }

        @Test
        fun `tsx files are source files`() {
            assertTrue(isSourceFile("Component.tsx"))
        }

        @Test
        fun `jsx files are source files`() {
            assertTrue(isSourceFile("Component.jsx"))
        }

        @Test
        fun `scala files are source files`() {
            assertTrue(isSourceFile("App.scala"))
        }

        @Test
        fun `go files are source files`() {
            assertTrue(isSourceFile("main.go"))
        }

        @Test
        fun `rust files are source files`() {
            assertTrue(isSourceFile("main.rs"))
        }

        @Test
        fun `swift files are source files`() {
            assertTrue(isSourceFile("App.swift"))
        }

        @Test
        fun `c files are source files`() {
            assertTrue(isSourceFile("main.c"))
        }

        @Test
        fun `cpp files are source files`() {
            assertTrue(isSourceFile("main.cpp"))
        }

        @Test
        fun `cc files are source files`() {
            assertTrue(isSourceFile("main.cc"))
        }

        @Test
        fun `h files are source files`() {
            assertTrue(isSourceFile("header.h"))
        }

        @Test
        fun `hpp files are source files`() {
            assertTrue(isSourceFile("header.hpp"))
        }

        @Test
        fun `csharp files are source files`() {
            assertTrue(isSourceFile("Program.cs"))
        }

        @Test
        fun `ruby files are source files`() {
            assertTrue(isSourceFile("app.rb"))
        }

        @Test
        fun `php files are source files`() {
            assertTrue(isSourceFile("index.php"))
        }

        @Test
        fun `groovy files are source files`() {
            assertTrue(isSourceFile("Build.groovy"))
        }

        @Test
        fun `dart files are source files`() {
            assertTrue(isSourceFile("main.dart"))
        }

        @Test
        fun `lua files are source files`() {
            assertTrue(isSourceFile("script.lua"))
        }

        @Test
        fun `xml files are not source files`() {
            assertFalse(isSourceFile("plugin.xml"))
        }

        @Test
        fun `json files are not source files`() {
            assertFalse(isSourceFile("package.json"))
        }

        @Test
        fun `yaml files are not source files`() {
            assertFalse(isSourceFile("config.yml"))
        }

        @Test
        fun `txt files are not source files`() {
            assertFalse(isSourceFile("readme.txt"))
        }

        @Test
        fun `md files are not source files`() {
            assertFalse(isSourceFile("README.md"))
        }

        @Test
        fun `files without extension are not source files`() {
            assertFalse(isSourceFile("Makefile"))
        }

        @Test
        fun `is case insensitive`() {
            assertTrue(isSourceFile("Main.JAVA"))
        }
    }
}
