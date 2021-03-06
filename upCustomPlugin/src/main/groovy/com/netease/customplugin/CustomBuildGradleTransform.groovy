package com.netease.customplugin

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import javassist.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

class CustomBuildGradleTransform extends Transform {

    private Project project
    ClassPool classPool
    String applicationName
    String applicationInjectPackage
    String iPluginInterfacePackage

    CustomBuildGradleTransform(Project project) {
        this.project = project
    }

    @Override
    void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        System.out.println("========================== CustomBuildGradleTransform start ===============================")

        getRealApplicationName(transformInvocation.getInputs())
        classPool = new ClassPool()
        applicationInjectPackage = project.rootProject.property("IApplicationInjectPackage")
        iPluginInterfacePackage = project.rootProject.property("IPluginInterfacePackage")
        System.out.println("iPluginInterfacePackage is   " + iPluginInterfacePackage)

        project.android.bootClasspath.each {
            classPool.appendClassPath((String) it.absolutePath)
        }
        def box = ConvertUtils.toCtClasses(transformInvocation.getInputs(), classPool)

        //要收集的application，一般情况下只有一个
        List<CtClass> applications = new ArrayList<>()
        //要收集的applicationInject，一般情况下有几个组件就有几个applicationInject
        List<CtClass> activators = new ArrayList<>()
        //要收集的继承了basePlugin
        List<CtClass> plugins = new ArrayList<>()

        for (CtClass ctClass : box) {
            if (isApplication(ctClass)) {
                applications.add(ctClass)
                continue
            }
            if (isActivator(ctClass)) {
                activators.add(ctClass)
            }
            if (isPlugin(ctClass)) {
                plugins.add(ctClass)
            }
        }
        for (CtClass ctClass : applications) {
            System.out.println("application is   " + ctClass.getName())
        }
        for (CtClass ctClass : activators) {
            System.out.println("applicationInject is   " + ctClass.getName())
        }
        for (CtClass ctClass : plugins) {
            System.out.println("iPluginInterfacePackage is   " + ctClass.getName())
        }

        transformInvocation.inputs.each { TransformInput input ->
            //对类型为jar文件的input进行遍历
            input.jarInputs.each { JarInput jarInput ->
                //jar文件一般是第三方依赖库jar文件
                // 重命名输出文件（同目录copyFile会冲突）
                def jarName = jarInput.name
                def md5Name = DigestUtils.md5Hex(jarInput.file.getAbsolutePath())
                if (jarName.endsWith(".jar")) {
                    jarName = jarName.substring(0, jarName.length() - 4)
                }
                //生成输出路径
                def dest = transformInvocation.outputProvider.getContentLocation(jarName + md5Name,
                        jarInput.contentTypes, jarInput.scopes, Format.JAR)
                //将输入内容复制到输出
                FileUtils.copyFile(jarInput.file, dest)

            }
            //对类型为“文件夹”的input进行遍历
            input.directoryInputs.each { DirectoryInput directoryInput ->
                boolean isRegisterCompoAuto = project.extensions.upBuildGradle.isAutoRegisterComponent
                if (isRegisterCompoAuto) {
                    String fileName = directoryInput.file.absolutePath
                    File dir = new File(fileName)
                    dir.eachFileRecurse { File file ->
                        String filePath = file.absolutePath
                        String classNameTemp = filePath.replace(fileName, "")
                                .replace("\\", ".")
                                .replace("/", ".")
                        if (classNameTemp.endsWith(".class")) {
                            String className = classNameTemp.substring(1, classNameTemp.length() - 6)
                            if (className.equals(applicationName)) {
                                injectApplicationCode(applications.get(0), activators, fileName,plugins)
                            }
                        }
                    }
                }
                def dest = transformInvocation.outputProvider.getContentLocation(directoryInput.name,
                        directoryInput.contentTypes,
                        directoryInput.scopes, Format.DIRECTORY)
                // 将input的目录复制到output指定目录
                FileUtils.copyDirectory(directoryInput.file, dest)
            }
        }
        System.out.println("========================== CustomBuildGradleTransform end ===============================")
    }


    private void getRealApplicationName(Collection<TransformInput> inputs) {
        applicationName = project.extensions.upBuildGradle.applicationName
        if (applicationName == null || applicationName.isEmpty()) {
            throw new RuntimeException("you should set applicationName in upBuildGradle")
        }
    }


    private void injectApplicationCode(CtClass ctClassApplication, List<CtClass> activators, String patch,List<CtClass> plugins) {
        System.out.println("injectApplicationCode begin")
        ctClassApplication.defrost()
        try {
            CtMethod attachBaseContextMethod = ctClassApplication.getDeclaredMethod("onCreate", null)
            attachBaseContextMethod.insertAfter(getAutoLoadComCode(activators))
            attachBaseContextMethod.insertAfter(getAutoLoadPlugin(plugins))
        } catch (CannotCompileException | NotFoundException e) {
            StringBuilder methodBody = new StringBuilder()
            methodBody.append("protected void onCreate() {")
            methodBody.append("super.onCreate();")
            methodBody.append(getAutoLoadComCode(activators))
            methodBody.append(getAutoLoadComCode(plugins))
            methodBody.append("}")
            ctClassApplication.addMethod(CtMethod.make(methodBody.toString(), ctClassApplication))
        } catch (Exception e) {

        }
        ctClassApplication.writeFile(patch)
        ctClassApplication.detach()

        System.out.println("injectApplicationCode success ")
    }

    private String getAutoLoadComCode(List<CtClass> activators) {
        StringBuilder autoLoadComCode = new StringBuilder()
        for (CtClass ctClass : activators) {
            autoLoadComCode.append("new " + ctClass.getName() + "()" + ".onCreate();")
        }

        return autoLoadComCode.toString()
    }

    private String getAutoLoadPlugin(List<CtClass> plugins) {
        StringBuilder autoLoadComCode = new StringBuilder()
        for (CtClass ctClass : plugins) {
            autoLoadComCode.append("new " + ctClass.getName() + "()" + ".init();")
        }

        return autoLoadComCode.toString()
    }



    private boolean isApplication(CtClass ctClass) {
        try {
            if (applicationName != null && applicationName.equals(ctClass.getName())) {
                return true
            }
        } catch (Exception e) {
            println "class not found exception class name:  " + ctClass.getName()
        }
        return false
    }

    private boolean isActivator(CtClass ctClass) {
        try {
            for (CtClass ctClassInter : ctClass.getInterfaces()) {
                if (applicationInjectPackage.equals(ctClassInter.name)) {
                    return true
                }
            }
        } catch (Exception e) {
            println "class not found exception class name:  " + ctClass.getName()
        }

        return false
    }

    private boolean isPlugin(CtClass ctClass) {
        try {
            for (CtClass ctClassInter : ctClass.getInterfaces()) {
                if (iPluginInterfacePackage.equals(ctClassInter.name)) {
                    return true
                }
            }
        } catch (Exception e) {
            println "class not found exception class name:  " + ctClass.getName()
        }

        return false
    }

    @Override
    String getName() {
        return "ComponentCode"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

}