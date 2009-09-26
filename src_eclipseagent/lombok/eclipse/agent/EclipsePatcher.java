/*
 * Copyright © 2009 Reinier Zwitserloot and Roel Spilker.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.agent;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.Opcodes;

import lombok.Lombok;
import lombok.core.SpiLoadUtil;
import lombok.patcher.Hook;
import lombok.patcher.MethodTarget;
import lombok.patcher.ScriptManager;
import lombok.patcher.StackRequest;
import lombok.patcher.scripts.AddFieldScript;
import lombok.patcher.scripts.ExitFromMethodEarlyScript;
import lombok.patcher.scripts.WrapReturnValuesScript;

/**
 * This is a java-agent that patches some of eclipse's classes so AST Nodes are handed off to Lombok
 * for modification before Eclipse actually uses them to compile, render errors, show code outlines,
 * create auto-completion dialogs, and anything else eclipse does with java code. See the *Transformer
 * classes in this package for more information about which classes are transformed and how they are
 * transformed.
 */
public class EclipsePatcher {
	private EclipsePatcher() {}
	
	private static Map<String, EclipseTransformer> transformers = new HashMap<String, EclipseTransformer>();
	static {
		try {
			for ( EclipseTransformer transformer : SpiLoadUtil.findServices(EclipseTransformer.class) ) {
				String targetClassName = transformer.getTargetClassName();
				transformers.put(targetClassName, transformer);
			}
		} catch ( Throwable t ) {
			throw Lombok.sneakyThrow(t);
		}
	}
	
	private static class Patcher implements ClassFileTransformer {
		public byte[] transform(ClassLoader loader, String className,
				Class<?> classBeingRedefined,
				ProtectionDomain protectionDomain, byte[] classfileBuffer)
				throws IllegalClassFormatException {
			
//			ClassLoader classLoader = Patcher.class.getClassLoader();
//			if ( classLoader == null ) classLoader = ClassLoader.getSystemClassLoader();
			
			EclipseTransformer transformer = transformers.get(className);
			if ( transformer != null ) return transformer.transform(classfileBuffer);
			
			return null;
		}
	}
	
	public static void agentmain(String agentArgs, Instrumentation instrumentation) throws Exception {
		registerPatcher(instrumentation, true);
		addLombokToSearchPaths(instrumentation);
	}
	
	private static void addLombokToSearchPaths(Instrumentation instrumentation) throws Exception {
		String path = findPathOfOurClassloader();
		//On java 1.5, you don't have these methods, so you'll be forced to manually -Xbootclasspath/a them in.
		tryCallMethod(instrumentation, "appendToSystemClassLoaderSearch", path + "/lombok.jar");
		tryCallMethod(instrumentation, "appendToBootstrapClassLoaderSearch", path + "/lombok.eclipse.agent.jar");
	}
	
	private static void tryCallMethod(Object o, String methodName, String path) {
		try {
			Instrumentation.class.getMethod(methodName, JarFile.class).invoke(o, new JarFile(path));
		} catch ( Throwable ignore ) {}
	}
	
	private static String findPathOfOurClassloader() throws Exception {
		URI uri = EclipsePatcher.class.getResource("/" + EclipsePatcher.class.getName().replace('.', '/') + ".class").toURI();
		Pattern p = Pattern.compile("^jar:file:([^\\!]+)\\!.*\\.class$");
		Matcher m = p.matcher(uri.toString());
		if ( !m.matches() ) return ".";
		String rawUri = m.group(1);
		return new File(URLDecoder.decode(rawUri, Charset.defaultCharset().name())).getParent();
	}
	
	public static void premain(String agentArgs, Instrumentation instrumentation) throws Exception {
		registerPatcher(instrumentation, false);
		addLombokToSearchPaths(instrumentation);
		ScriptManager sm = new ScriptManager();
		sm.registerTransformer(instrumentation);
		
		sm.addScript(new WrapReturnValuesScript(
				new MethodTarget("org.eclipse.jdt.core.dom.ASTConverter", "retrieveStartingCatchPosition"),
				new Hook("java/lombok/eclipse/PatchFixes", "fixRetrieveStartingCatchPosition", "(I)I"),
				StackRequest.PARAM1));
		
		sm.addScript(new AddFieldScript("org.eclipse.jdt.internal.compiler.ast.ASTNode",
				Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, "$generatedBy", "Lorg/eclipse/jdt/internal/compiler/ast/ASTNode;"));
		
		sm.addScript(new AddFieldScript("org.eclipse.jdt.core.dom.ASTNode",
				Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, "$isGenerated", "Z"));
		
		sm.addScript(new AddFieldScript("org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration",
				Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT, "$lombokAST", "Ljava/lang/Object;"));
		
		sm.addScript(new WrapReturnValuesScript(
				new MethodTarget("org.eclipse.jdt.internal.compiler.parser.Parser", "getMethodBodies", "void",
						"org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration"),
						new Hook("java/lombok/eclipse/ClassLoaderWorkaround", "transformCompilationUnitDeclaration",
								"(Ljava/lang/Object;Ljava/lang/Object;)V"), StackRequest.THIS, StackRequest.PARAM1));
		
		sm.addScript(new WrapReturnValuesScript(
				new MethodTarget("org.eclipse.jdt.internal.compiler.parser.Parser", "endParse",
						"org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration", "int"),
						new Hook("java/lombok/eclipse/ClassLoaderWorkaround", "transformCompilationUnitDeclarationSwapped",
								"(Ljava/lang/Object;Ljava/lang/Object;)V"), StackRequest.THIS, StackRequest.RETURN_VALUE));
		
		sm.addScript(new ExitFromMethodEarlyScript(
				new MethodTarget("org.eclipse.jdt.internal.compiler.parser.Parser", "parse", "void",
						"org.eclipse.jdt.internal.compiler.ast.MethodDeclaration",
						"org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration"),
						new Hook("java/lombok/eclipse/PatchFixes", "checkBit24",
								"(Ljava/lang/Object;)Z"), null, StackRequest.PARAM1));
		
		sm.addScript(new ExitFromMethodEarlyScript(
				new MethodTarget("org.eclipse.jdt.internal.compiler.parser.Parser", "parse", "void",
						"org.eclipse.jdt.internal.compiler.ast.ConstructorDeclaration",
						"org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration", "boolean"),
						new Hook("java/lombok/eclipse/PatchFixes", "checkBit24",
								"(Ljava/lang/Object;)Z"), null, StackRequest.PARAM1));
		
		sm.addScript(new ExitFromMethodEarlyScript(
				new MethodTarget("org.eclipse.jdt.internal.compiler.parser.Parser", "parse", "void",
						"org.eclipse.jdt.internal.compiler.ast.Initializer",
						"org.eclipse.jdt.internal.compiler.ast.TypeDeclaration",
						"org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration"),
						new Hook("java/lombok/eclipse/PatchFixes", "checkBit24",
								"(Ljava/lang/Object;)Z"), null, StackRequest.PARAM1));
	}
	
	private static void registerPatcher(Instrumentation instrumentation, boolean transformExisting) throws IOException {
		instrumentation.addTransformer(new Patcher()/*, true*/);
		
		if ( transformExisting ) for ( Class<?> c : instrumentation.getAllLoadedClasses() ) {
			if ( transformers.containsKey(c.getName()) ) {
				try {
					//instrumentation.retransformClasses(c); - //not in java 1.5.
					Instrumentation.class.getMethod("retransformClasses", Class[].class).invoke(instrumentation,
							new Object[] { new Class[] {c }});
				} catch ( InvocationTargetException e ) {
					throw new UnsupportedOperationException(
							"The eclipse parser class is already loaded and cannot be modified. " +
							"You'll have to restart eclipse in order to use Lombok in eclipse.");
				} catch ( Throwable t ) {
					throw new UnsupportedOperationException(
							"This appears to be a java 1.5 instance, which cannot reload already loaded classes. " +
					"You'll have to restart eclipse in order to use Lombok in eclipse.");
				}
			}
		}
	}
}
