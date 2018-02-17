package com.github.unafraid.gradle

import org.ajoberstar.grgit.Grgit
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException

/**
 * Use a (possibly private) github repo as a maven dependency.
 * @since 7/16/14
 * @author drapp , UnAfraid
 */
class GitRepoPlugin implements Plugin<Project> {
    static repoCache = [:]

    void apply(Project project) {

        project.extensions.create("gitPublishConfig", GitPublishConfig)

        // allow declaring special repositories
        if (!project.repositories.metaClass.respondsTo(project.repositories, 'github', String, String, String, String, String, Object)) {
            project.repositories.metaClass.github = { String org, String repo, String upstream = "origin", String branch = "master", String type = "releases", def closure = null ->
                String gitUrl = "git@github.com:${org}/${repo}.git"
                def orgDir = repositoryDir(project, org)
                addLocalRepo(project, ensureLocalRepo(project, orgDir, repo, gitUrl, upstream, branch), type)
            }
        }

        if (!project.repositories.metaClass.respondsTo(project.repositories, 'bitbucket', String, String, String, String, String, Object)) {
            project.repositories.metaClass.bitbucket = { String org, String repo, String upstream = "origin", String branch = "master", String type = "releases", def closure = null ->
                String gitUrl = "git@bitbucket.org:${org}/${repo}.git"
                def orgDir = repositoryDir(project, org)
                addLocalRepo(project, ensureLocalRepo(project, orgDir, repo, gitUrl, upstream, branch), type)
            }
        }

        if (!project.repositories.metaClass.respondsTo(project.repositories, 'git', String, String, String, String, String, Object)) {
            project.repositories.metaClass.git = { String gitUrl, String name, String upstream = "origin", String branch = "master", String type = "releases", def closure = null ->
                def orgDir = repositoryDir(project, name)
                addLocalRepo(project, ensureLocalRepo(project, orgDir, name, gitUrl, upstream, branch), type)
            }
        }

        project.afterEvaluate {
            if (hasPublishTask(project)) {
                // add a publishToGithub task
                Task cloneRepo = project.tasks.create("cloneRepo")
                cloneRepo.doFirst {
                    ensureLocalRepo(
                            project,
                            repositoryDir(project, project.gitPublishConfig.org),
                            project.gitPublishConfig.repo,
                            gitCloneUrl(project),
                            project.gitPublishConfig.upstream,
                            project.gitPublishConfig.branch)
                }
                publishTask(project).dependsOn(cloneRepo)

                Task publishAndPush = project.tasks.create(project.gitPublishConfig.publishAndPushTask)
                publishAndPush.doFirst {
                    def gitDir = repositoryDir(project, project.gitPublishConfig.org + '/' + project.gitPublishConfig.repo)
                    def gitRepo = Grgit.open(dir: gitDir)

                    gitRepo.add(patterns: ['.'])
                    gitRepo.commit(message: "Published artifacts ${project.getGroup()}:${project.getName()}:${project.version}")
                    gitRepo.push()
                }
                publishAndPush.dependsOn(publishTask(project))
            }
        }
    }

    private static boolean hasPublishTask(project) {
        try {
            publishTask(project)
            return true
        } catch (UnknownTaskException e) {
            return false
        }
    }

    private static Task publishTask(Project project) {
        project.tasks.getByName(project.gitPublishConfig.publishTask)
    }

    private static File repositoryDir(Project project, String name) {
        if (project.hasProperty("gitRepoHome")) {
            return project.file("${project.property('gitRepoHome')}/$name")
        } else {
            return project.file("${System.properties['user.home']}/.gitRepos/$name")
        }
    }

    private static String gitCloneUrl(Project project) {
        if (project.gitPublishConfig.gitUrl != "") {
            return project.gitPublishConfig.gitUrl
        } else {
            return "git@${project.gitPublishConfig.provider}:${project.gitPublishConfig.org}/${project.gitPublishConfig.repo}.git"
        }
    }

    private static File ensureLocalRepo(Project project, File directory, String name, String gitUrl, String upstream, String branch) {
        if (project.gitPublishConfig.useCache) {
            def cacheKey = "${directory.path}:${name}:${gitUrl}:${branch}"
            if (repoCache.containsKey(cacheKey)) {
                return repoCache[cacheKey]
            }
        }

        def repoDir = new File(directory, name)
        def gitRepo

        if (repoDir.directory || project.hasProperty("offline")) {
            gitRepo = Grgit.open(dir: repoDir)
        } else {
            gitRepo = Grgit.clone(dir: repoDir, uri: gitUrl)
        }

        if (!project.hasProperty("offline")) {
            if (gitRepo.branch.list().find { it.name == branch }) {
                gitRepo.checkout(branch: branch)
            } else {
                gitRepo.checkout(branch: branch, startPoint: upstream + '/' + branch, createBranch: true)
            }
            gitRepo.pull()
        }

        if (project.gitPublishConfig.useCache) {
            def cacheKey = "${directory.path}:${name}:${gitUrl}:${branch}"
            repoCache[cacheKey] = repoDir
        }

        return repoDir
    }

    private static void addLocalRepo(Project project, File repoDir, String type) {
        project.repositories.maven {
            url repoDir.getAbsolutePath() + "/" + type
        }
    }
}

class GitPublishConfig {
    String org = ""
    String repo = ""
    String provider = "github.com" //github.com, gitlab or others
    String gitUrl = "" //used to replace git@${provider}:${org}/${repo}.git
    String home = "${System.properties['user.home']}/.gitRepos"
    String upstream = "origin"
    String branch = "master"
    String publishAndPushTask = "publishToGithub"
    String publishTask = "publish" //default publish tasks added by maven-publish plugin
    boolean useCache = true
}
