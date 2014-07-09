import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*

import static liveplugin.PluginUtil.*

registerAction("FindAllJavaClassDependencies", "alt shift D", TOOLS_MENU, "Find All Dependencies") { AnActionEvent event ->
    def project = event.project
    def document = currentDocumentIn(project)
    def psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)

    doInBackground("Looking for dependencies of ${psiFile.name}") { ProgressIndicator indicator ->
        runReadAction {
            def fileIndex = ProjectRootManager.getInstance(project).fileIndex
            def isInProject = { PsiFileSystemItem item ->
                item?.virtualFile != null && fileIndex.isInSource(item.virtualFile) && !fileIndex.isInLibrarySource(item.virtualFile)
            }


            def allFiles = allSourceFilesIn(project, indicator)
            def dependenciesByFile = allFiles.collectEntries { file ->
                [file, dependenciesWithStrengh(file).findAll{ isInProject(it.key) }]
            }
            // TODO
//            dependenciesByFile.entrySet().each {
//                def file = it.key
//                def depenencies = it.value
//                def backwardDependencies = dependenciesByFile
//            }
            def indexByFile = [allFiles, 0..<allFiles.size()].transpose().collectEntries{ it }


            def allFilesAsJs = allFiles.collect{ """{"name": "${it.name}", "group": 1}""".trim() }.join(",\n")
            def nodesJsLiteral = """"nodes": [\n${allFilesAsJs}\n]"""

            def allFilesDependenciesJs = dependenciesByFile.entrySet().collectMany {
                def file = it.key
                def dependencies = it.value
                if (dependencies.isEmpty()) return []
                def srcIndex = indexByFile.get(file)
                dependencies.collect { dependencyWithStrength ->
                    def dependency = dependencyWithStrength.key
                    def strength = dependencyWithStrength.value
                    def targetIndex = indexByFile.get(dependency)
                    """{"source": ${srcIndex}, "target": ${targetIndex}, "value": ${strength}}"""
                }
            }.join(",\n")
            def linksJsLiteral = """"links": [\n${allFilesDependenciesJs}\n]"""

            // copy-pasteable into committers-changing-same-files-graph.html
            show(nodesJsLiteral + ",\n" + linksJsLiteral)
        }
    }
}
if (!isIdeStartup) show("reloaded")

List<PsiFileSystemItem> allSourceFilesIn(Project project, ProgressIndicator indicator) {
    def allFiles = []
    def fileIndex = ProjectRootManager.getInstance(project).fileIndex

    def iterator = new ContentIterator() {
        @Override boolean processFile(VirtualFile file) {
            if (!file.directory && fileIndex.isInSource(file) && !fileIndex.isInLibrarySource(file)) {
                allFiles.add(file)
            }
            !indicator.canceled
        }
    }
    fileIndex.iterateContent(iterator)
    allFiles.collect{ PsiManager.getInstance(project).findFile(it) }
}

Map<PsiFileSystemItem, Integer> dependenciesWithStrengh(PsiFileSystemItem item) {
    def dependencies = new HashMap().withDefault{ 0 }
    def addDependency = { PsiFileSystemItem psiFileItem ->
        dependencies.put(psiFileItem, dependencies.get(psiFileItem) + 1)
    }
    def visitor = new JavaRecursiveElementVisitor() {
        @Override void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
            def element = reference.resolve()
            if (element == null) {
                show("NOT resolved for " + reference)
                return
            }
            addDependency(rootPsiOf(element))
        }

        @Override void visitImportList(PsiImportList list) {
            for (statement in list.importStatements) {
                addDependency(rootPsiOf(statement.importReference.element))
            }
            for (statement in list.importStaticStatements) {
                addDependency(rootPsiOf(statement.importReference.element))
            }
        }
    }
    item.acceptChildren(visitor)

    dependencies.remove(item)
    dependencies.remove(null)
    dependencies
}

PsiFileSystemItem rootPsiOf(PsiElement element) {
    if (element == null) null
    else if (element instanceof PsiFileSystemItem) element
    else rootPsiOf(element.parent)
}
