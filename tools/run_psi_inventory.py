#!/usr/bin/env python3
"""Bootstrap a temporary Gradle/Kotlin PSI analyzer and write conservative reports."""
import json, pathlib, subprocess, sys, tempfile, textwrap

source = pathlib.Path(sys.argv[1]); mapping_path = pathlib.Path(sys.argv[2]); out = pathlib.Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)
if not source.exists(): raise SystemExit(f"Missing source: {source}")
if not mapping_path.exists(): raise SystemExit(f"Missing mapping: {mapping_path}")
# Create isolated analyzer project. Kotlin PSI is obtained from kotlin-compiler-embeddable.
tmp = pathlib.Path(tempfile.mkdtemp(prefix="mris-psi-"))
(tmp / "settings.gradle.kts").write_text('rootProject.name = "mris-psi-analyzer"\n')
(tmp / "build.gradle.kts").write_text(textwrap.dedent('''
plugins { kotlin("jvm") version "2.0.21"; application }
application { mainClass.set("MainKt") }
repositories { mavenCentral() }
dependencies { implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21") }
'''))
(tmp / "src/main/kotlin/Main.kt").write_text(textwrap.dedent(r''' 
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment.Companion.createForProduction
import java.io.File

fun main(args: Array<String>) {
  val source = File(args[0]); val output = File(args[1])
  val disposable = Disposer.newDisposable()
  try {
    val env = createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
    val psi = KtPsiFactory(env.project, false).createFile(source.name, source.readText())
    val records = mutableListOf<Map<String, Any>>()
    for (d in psi.declarations) {
      if (d !is KtNamedDeclaration) continue
      val name = d.name ?: continue
      val kind = when (d) {
        is KtClassOrObject -> when { d.isInterface() -> "interface"; d.isEnum() -> "enum"; d.isAnnotation() -> "annotation"; d.isObject() -> "object"; else -> "class" }
        is KtNamedFunction -> "function"
        is KtProperty -> "property"
        is KtTypeAlias -> "typealias"
        else -> d.javaClass.simpleName
      }
      val start = d.textRange.startOffset; val end = d.textRange.endOffset
      val lineStart = source.readText().substring(0, start).count { it == '\n' } + 1
      val lineEnd = source.readText().substring(0, end).count { it == '\n' } + lineStart
      records += mapOf("name" to name, "kind" to kind, "startOffset" to start, "endOffset" to end, "startLine" to lineStart, "endLine" to lineEnd, "text" to d.text)
    }
    output.writeText(records.joinToString(prefix="[", postfix="]") { r ->
      val safe = r.mapValues { (_, v) -> if (v is String) v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") else v }
      safe.entries.joinToString(prefix="{", postfix="}") { (k,v) -> "\"$k\":${if(v is String) "\"$v\"" else v}" }
    })
  } finally { Disposer.dispose(disposable) }
}
'''))
# Copy source and run analyzer; mapping application and reports are done in Python.
copy = tmp / "input.kt"; copy.write_bytes(source.read_bytes())
subprocess.run(["gradle", "--no-daemon", "run", "--args", f'"{copy}" "{tmp / "inventory.json"}"'], cwd=tmp, check=True)
records = json.loads((tmp / "inventory.json").read_text())
mapping = json.loads(mapping_path.read_text())
name_to_modules = {}
for module, names in mapping.get("modules", {}).items():
    for name in names: name_to_modules.setdefault(name, []).append(module)
for r in records:
    modules = name_to_modules.get(r["name"], [])
    r["mappedModules"] = modules
    r["mappingStatus"] = "mapped" if len(modules) == 1 else "unmapped" if not modules else "duplicate-mapping"
(out / "psi-inventory.json").write_text(json.dumps(records, indent=2, ensure_ascii=False))
plan = {"source": str(source), "sourceBytes": source.stat().st_size, "declarationCount": len(records), "mapped": sum(r["mappingStatus"] == "mapped" for r in records), "unmapped": [r for r in records if r["mappingStatus"] == "unmapped"], "duplicateMappings": [r for r in records if r["mappingStatus"] == "duplicate-mapping"], "items": [{k:r[k] for k in ("name","kind","startLine","endLine","mappedModules","mappingStatus")} for r in records]}
(out / "extraction-plan.json").write_text(json.dumps(plan, indent=2, ensure_ascii=False))
(out / "phase3-summary.md").write_text(f"# Phase 3 PSI Report\n\n- Declarations: {len(records)}\n- Exactly mapped: {plan['mapped']}\n- Unmapped: {len(plan['unmapped'])}\n- Duplicate mappings: {len(plan['duplicateMappings'])}\n\nThis is a review plan only. No source files were extracted or changed.\n")
print(f"PSI inventory complete: {len(records)} declarations; mapped={plan['mapped']}; unmapped={len(plan['unmapped'])}; duplicate={len(plan['duplicateMappings'])}")
