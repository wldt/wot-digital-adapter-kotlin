plugins {
//    id("org.danilopianini.gradle-pre-commit-git-hooks") version "2.0.22"
    id("com.gradle.develocity") version "4.0"
}

// Modify HERE the name of the project
rootProject.name = "kotlin-template-project"

develocity {
    buildScan {
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
        publishing.onlyIf { it.buildResult.failures.isNotEmpty() } // Publish the build scan when the build fails
    }
}

/*
    If you want you can activate the following git hooks that automatically performs:
    - preCommit: static qa code analysis (recommended)
    - commitMsg: conventional commit format check (recommended)
    - post-commit: verifies that the commit is signed (optional)
    (N.B. you are required to be in a Git project)
    In order to use them you need to activate the "org.danilopianini.gradle-pre-commit-git-hooks" plugin above.
 */

// gitHooks {
//    preCommit {
//        tasks("detekt")
//        tasks("ktlintCheck")
//    }
//
//    commitMsg {
//        conventionalCommits()
//    }
//
//    hook("post-commit") {
//        from {
//            "git verify-commit HEAD &> /dev/null; " +
//                "if (( $? == 1 )); then echo -e '\\033[0;31mWARNING(COMMIT UNVERIFIED): commit NOT signed\\033[0m';" +
//                "else echo -e '\\033[0;32mOK COMMIT SIGNED\\033[0m'; fi"
//        }
//    }
//
//    createHooks(true)
// }
