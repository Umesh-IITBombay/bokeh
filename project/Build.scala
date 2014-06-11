import sbt._
import Keys._

import com.untyped.sbtjs.Plugin.{JsKeys,jsSettings=>pluginJsSettings,CompilationLevel,VariableRenamingPolicy}

import WorkbenchPlugin.{WorkbenchKeys,workbenchSettings=>pluginWorkbenchSettings}
import LessPlugin.{LessKeys,lessSettings=>pluginLessSettings}
import EcoPlugin.{EcoKeys,ecoSettings=>pluginEcoSettings}

object ProjectBuild extends Build {
    override lazy val settings = super.settings ++ Seq(
        organization := "org.continuumio",
        version := "0.4.2-SNAPSHOT",
        description := "Bokeh plotting library",
        homepage := Some(url("http://bokeh.pydata.org")),
        licenses := Seq("BSD-style" -> url("http://www.opensource.org/licenses/bsd-license.php")),
        scalaVersion := "2.10.3",
        scalacOptions ++= Seq("-Xlint", "-deprecation", "-unchecked", "-feature"),
        shellPrompt := { state =>
            "continuum (%s)> ".format(Project.extract(state).currentProject.id)
        },
        cancelable := true,
        resolvers ++= Seq(
            Resolver.sonatypeRepo("releases"),
            Resolver.sonatypeRepo("snapshots"),
            Resolver.typesafeRepo("releases"),
            Resolver.typesafeRepo("snapshots"))
    )

    val eco = taskKey[Seq[File]]("Compile ECO templates")

    val requirejs = taskKey[(File, File)]("Run RequireJS optimizer")
    val requirejsConfig = settingKey[RequireJSConfig]("RequireJS configuration")

    val vendorStyles = settingKey[Seq[String]]("Paths to vendor styles")
    val bokehStyles = settingKey[Seq[String]]("Paths to Bokeh styles")

    val build = taskKey[Unit]("Build CoffeeScript, LESS, ECO, etc.")
    val deploy = taskKey[Unit]("Generate bokeh(.min).{js,css}")

    lazy val workbenchSettings = pluginWorkbenchSettings ++ Seq(
        WorkbenchKeys.reloadBrowsers <<= WorkbenchKeys.reloadBrowsers triggeredBy (compile in Compile),
        resourceGenerators in Compile <+= Def.task {
            val output = (resourceManaged in (Compile, JsKeys.js) value) / "workbench.js"
            val script = WorkbenchKeys.renderScript.value
            IO.write(output, script)
            Seq(output)
        })

    lazy val jsSettings = pluginJsSettings ++ Seq(
        sourceDirectory in (Compile, JsKeys.js) <<= (sourceDirectory in Compile)(_ / "coffee"),
        resourceManaged in (Compile, JsKeys.js) <<= (resourceManaged in Compile)(_ / "js"),
        compile in Compile <<= compile in Compile dependsOn (JsKeys.js in Compile),
        JsKeys.compilationLevel in (Compile, JsKeys.js) := CompilationLevel.WHITESPACE_ONLY,
        JsKeys.variableRenamingPolicy in (Compile, JsKeys.js) := VariableRenamingPolicy.OFF,
        JsKeys.prettyPrint in (Compile, JsKeys.js) := true)

    lazy val lessSettings = pluginLessSettings ++ Seq(
        sourceDirectory in (Compile, LessKeys.less) <<= (sourceDirectory in Compile)(_ / "less"),
        resourceManaged in (Compile, LessKeys.less) <<= (resourceManaged in Compile)(_ / "css"),
        compile in Compile <<= compile in Compile dependsOn (LessKeys.less in Compile),
        includeFilter in (Compile, LessKeys.less) := "main.less")

    lazy val ecoSettings = pluginEcoSettings ++ Seq(
        sourceDirectory in (Compile, EcoKeys.eco) <<= (sourceDirectory in Compile)(_ / "coffee"),
        resourceManaged in (Compile, EcoKeys.eco) <<= (resourceManaged in Compile)(_ / "js"),
        compile in Compile <<= compile in Compile dependsOn (EcoKeys.eco in Compile))

    lazy val requirejsSettings = Seq(
        requirejsConfig in Compile := {
            val srcDir = sourceDirectory in Compile value;
            val jsDir = resourceManaged in (Compile, JsKeys.js) value;
            RequireJSConfig(
                logLevel       = 2,
                name           = "vendor/almond/almond",
                baseUrl        = jsDir,
                mainConfigFile = jsDir / "config.js",
                include        = List("underscore", "main"),
                wrapShim       = true,
                wrap           = RequireJSWrap(
                    startFile  = srcDir / "js" / "_start.js.frag",
                    endFile    = srcDir / "js" / "_end.js.frag"
                ),
                optimize       = "none",
                out            = jsDir / "bokeh.js")
        },
        requirejs in Compile <<= Def.task {
            val config = (requirejsConfig in Compile).value
            val rjs = new RequireJS(streams.value.log)
            rjs.optimize(config)
        } dependsOn (build in Compile))

    lazy val pluginSettings = /*workbenchSettings ++*/ jsSettings ++ lessSettings ++ ecoSettings ++ requirejsSettings

    lazy val bokehjsSettings = Project.defaultSettings ++ pluginSettings ++ Seq(
        sourceDirectory in Compile := baseDirectory.value / "src",
        resourceManaged in Compile := baseDirectory.value / "build",
        resourceGenerators in Compile <+= Def.task {
            val srcDir = sourceDirectory in Compile value
            val resDir = resourceManaged in (Compile, JsKeys.js) value
            val source = srcDir / "vendor"
            val target = resDir / "vendor"
            val toCopy = (PathFinder(source) ***) pair Path.rebase(source, target)
            IO.copy(toCopy, overwrite=true).toSeq
        },
        vendorStyles := List(
            "jquery-ui-amd/jquery-ui-1.10.0/themes/base/jquery-ui.css",
            "jstree/dist/themes/default/style.min.css",
            "handsontable/jquery.handsontable.css",
            "jqrangeslider/classic.css"),
        bokehStyles := List("main.css"),
        resourceGenerators in Compile <+= Def.task {
            def concat(files: Seq[File]): String =
                files.map(file => IO.read(file)).mkString("\n")

            def minify(in: File, out: File) {
                import com.yahoo.platform.yui.compressor.CssCompressor
                val css = new CssCompressor(new java.io.FileReader(in))
                css.compress(new java.io.FileWriter(out), 80)
            }

            val log = streams.value.log

            val vendorDir = (sourceDirectory in Compile value) / "vendor"
            val cssDir = resourceManaged in (Compile, LessKeys.less) value

            val vendor = vendorStyles.value.map(vendorDir / _)
            val bokeh = bokehStyles.value.map(cssDir / _)

            val vendorCss = cssDir / "bokeh-vendor.css"
            val vendorMinCss = cssDir / "bokeh-vendor.min.css"
            val vendorOnly = concat(vendor)
            log.info(s"Writing $vendorCss")
            IO.write(vendorCss, vendorOnly)
            log.info(s"Minifying $vendorMinCss")
            // minify(vendorCss, vendorMinCss)
            IO.write(vendorMinCss, vendorOnly)

            val bokehCss = cssDir / "bokeh.css"
            val bokehMinCss = cssDir / "bokeh.min.css"
            val vendorAndBokeh = concat(vendor ++ bokeh)
            log.info(s"Writing $bokehCss")
            IO.write(bokehCss, vendorAndBokeh)
            log.info(s"Minifying $bokehMinCss")
            // minify(bokehCss, bokehMinCss)
            IO.write(bokehMinCss, vendorAndBokeh)

            Seq(vendorCss, vendorMinCss, bokehCss, bokehMinCss)
        } dependsOn (LessKeys.less in Compile),
        build in Compile <<= Def.task {} dependsOn (resources in Compile),
        deploy in Compile <<= Def.task {} dependsOn (requirejs in Compile))

    lazy val bokeh = project in file(".") aggregate(bokehjs)
    lazy val bokehjs = project in file("bokehjs") settings(bokehjsSettings: _*)

    override def projects = Seq(bokeh, bokehjs)
}
