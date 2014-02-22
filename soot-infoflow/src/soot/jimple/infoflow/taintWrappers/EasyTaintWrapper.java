/*******************************************************************************
 * Copyright (c) 2012 Secure Software Engineering Group at EC SPRIDE.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package soot.jimple.infoflow.taintWrappers;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Value;
import soot.jimple.DefinitionStmt;
import soot.jimple.InstanceInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.solver.IInfoflowCFG;
import soot.jimple.infoflow.util.SootMethodRepresentationParser;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * A list of methods is passed which contains signatures of instance methods
 * that taint their base objects if they are called with a tainted parameter.
 * When a base object is tainted, all return values are tainted, too.
 * For static methods, only the return value is assumed to be tainted when
 * the method is called with a tainted parameter value.
 * 
 * @author Christian Fritz, Steven Arzt
 *
 */
public class EasyTaintWrapper extends AbstractTaintWrapper implements Cloneable {
	private final Logger logger = LoggerFactory.getLogger(getClass());
	private final Map<String, Set<String>> classList;
	private final Map<String, Set<String>> excludeList;
	private final Map<String, Set<String>> killList;
	private final Set<String> includeList;
	
	private LoadingCache<SootMethod, MethodWrapType> methodWrapCache = CacheBuilder.newBuilder().build
			(new CacheLoader<SootMethod, MethodWrapType>() {

				@Override
				public MethodWrapType load(SootMethod arg0) throws Exception {
					return getMethodWrapType(arg0.getSubSignature(), arg0.getDeclaringClass());
				}
				
			});
	
	private boolean aggressiveMode = false;
	private boolean alwaysModelEqualsHashCode = true;
	
	private enum MethodWrapType {
		CreateTaint,
		KillTaint,
		Exclude,
		NotRegistered
	}
	
	public EasyTaintWrapper(Map<String, Set<String>> classList){
		this(classList, new HashMap<String, Set<String>>(),
				new HashMap<String, Set<String>>(),
				new HashSet<String>());
	}
	
	public EasyTaintWrapper(Map<String, Set<String>> classList,
			Map<String, Set<String>> excludeList) {
		this(classList, excludeList, new HashMap<String, Set<String>>(),
				new HashSet<String>());
	}

	public EasyTaintWrapper(Map<String, Set<String>> classList,
			Map<String, Set<String>> excludeList,
			Map<String, Set<String>> killList) {
		this(classList, excludeList, killList, new HashSet<String>());
	}

	public EasyTaintWrapper(Map<String, Set<String>> classList,
			Map<String, Set<String>> excludeList,
			Map<String, Set<String>> killList,
			Set<String> includeList) {
		this.classList = classList;
		this.excludeList = excludeList;
		this.killList = killList;
		this.includeList = includeList;
	}

	public EasyTaintWrapper(String f) throws IOException{
        this(new File(f));
    }

	public EasyTaintWrapper(File f) throws IOException{
		BufferedReader reader = null;
		try{
			FileReader freader = new FileReader(f);
			reader = new BufferedReader(freader);
			String line = reader.readLine();
			List<String> methodList = new LinkedList<String>();
			List<String> excludeList = new LinkedList<String>();
			List<String> killList = new LinkedList<String>();
			this.includeList = new HashSet<String>();
			while(line != null){
				if (!line.isEmpty() && !line.startsWith("%"))
					if (line.startsWith("~"))
						excludeList.add(line.substring(1));
					else if (line.startsWith("-"))
						killList.add(line.substring(1));
					else if (line.startsWith("^"))
						includeList.add(line.substring(1));
					else
						methodList.add(line);
				line = reader.readLine();
			}
			this.classList = SootMethodRepresentationParser.v().parseClassNames(methodList, true);
			this.excludeList = SootMethodRepresentationParser.v().parseClassNames(excludeList, true);
			this.killList = SootMethodRepresentationParser.v().parseClassNames(killList, true);
			logger.info("Loaded wrapper entries for {} classes and {} exclusions.", classList.size(), excludeList.size());
		}
		finally {
			if (reader != null)
				reader.close();
		}
	}
	
	public EasyTaintWrapper(EasyTaintWrapper taintWrapper) {
		this(taintWrapper.classList, taintWrapper.excludeList, taintWrapper.killList, taintWrapper.includeList);
	}
	
	@Override
	public Set<AccessPath> getTaintsForMethod(Stmt stmt, AccessPath taintedPath,
			IInfoflowCFG icfg) {
		if (!stmt.containsInvokeExpr())
			return Collections.emptySet();
		
		final Set<AccessPath> taints = new HashSet<AccessPath>();
		final SootMethod method = stmt.getInvokeExpr().getMethod();
		
		// If the callee is a phantom class or has no body, we pass on the taint
		if (method.isPhantom() || !method.hasActiveBody())
			taints.add(taintedPath);

		// For the moment, we don't implement static taints on wrappers. Pass it on
		// not to break anything
		if(taintedPath.isStaticFieldRef())
			return Collections.singleton(taintedPath);

		// For implicit flows, we always taint the return value and the base
		// object on the empty abstraction.
		if (taintedPath.isEmpty()) {
			taints.add(taintedPath);
			if (stmt instanceof DefinitionStmt)
				taints.add(new AccessPath(((DefinitionStmt) stmt).getLeftOp(), true));
			if (stmt.containsInvokeExpr())
				if (stmt.getInvokeExpr() instanceof InstanceInvokeExpr)
					taints.add(new AccessPath(((InstanceInvokeExpr) stmt.getInvokeExpr()).getBase(), true));
			return taints;
		}
		
		// Do we handle equals() and hashCode() separately?
		final String subSig = method.getSubSignature();
		boolean taintEqualsHashCode = alwaysModelEqualsHashCode
				&& (subSig.equals("boolean equals(java.lang.Object)") || subSig.equals("int hashCode()"));
		
		// If this is not one of the supported classes, we skip it
		boolean isSupported = false;
		for (String supportedClass : this.includeList)
			if (method.getDeclaringClass().getName().startsWith(supportedClass)) {
				isSupported = true;
				break;
			}
		if (!isSupported && !aggressiveMode && !taintEqualsHashCode)
			return taints;
		
		// Check for a cached wrap type
		final MethodWrapType wrapType = methodWrapCache.getUnchecked(method);
		
		if (stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
			InstanceInvokeExpr iiExpr = (InstanceInvokeExpr) stmt.getInvokeExpr();			
			if (iiExpr.getBase().equals(taintedPath.getPlainValue())) {
				// If the base object is tainted, we have to check whether we must kill the taint
				if (wrapType == MethodWrapType.KillTaint)
					return Collections.emptySet();
				
				// If the base object is tainted, all calls to its methods always return
				// tainted values
				if (stmt instanceof DefinitionStmt) {
					DefinitionStmt def = (DefinitionStmt) stmt;

					// Check for exclusions
					if (wrapType != MethodWrapType.Exclude)
						taints.add(new AccessPath(def.getLeftOp(), true));
				}

				// If the base object is tainted, we pass this taint on
				taints.add(taintedPath);
			}
		}
				
		//if param is tainted && classList contains classname && if list. contains signature of method -> add propagation
		if (isSupported && wrapType == MethodWrapType.CreateTaint)
			for (Value param : stmt.getInvokeExpr().getArgs()) {
				if (param.equals(taintedPath.getPlainValue())) {
					// If we call a method on an instance with a tainted parameter, this
					// instance (base object) is assumed to be tainted.
					if (!taintEqualsHashCode)
						if (stmt.getInvokeExprBox().getValue() instanceof InstanceInvokeExpr)
							taints.add(new AccessPath(((InstanceInvokeExpr) stmt.getInvokeExprBox().getValue()).getBase(), true));
					
					// If make sure to also taint the left side of an assignment
					// if the object just got tainted 
					if (stmt instanceof DefinitionStmt)
						taints.add(new AccessPath(((DefinitionStmt) stmt).getLeftOp(), true));
				}
				
				// The parameter as such stays tainted
				taints.add(taintedPath);
			}
		
		return taints;
	}
	
	/**
	 * Checks whether at least one method in the given class is registered in
	 * the taint wrapper
	 * @param parentClass The class to check
	 * @param newTaints Check the list for creating new taints
	 * @param killTaints Check the list for killing taints
	 * @param excludeTaints Check the list for excluding taints
	 * @return True if at least one method of the given class has been registered
	 * with the taint wrapper, otherwise
	 */
	private boolean hasWrappedMethodsForClass(SootClass parentClass,
			boolean newTaints, boolean killTaints, boolean excludeTaints) {
		if (newTaints && classList.containsKey(parentClass.getName()))
			return true;
		if (excludeTaints && excludeList.containsKey(parentClass.getName()))
			return true;
		if (killTaints && killList.containsKey(parentClass.getName()))
			return true;
		return false;
	}
	
	/**
	 * Gets the type of action the taint wrapper shall perform on a given method
	 * @param subSig The subsignature of the method to look for
	 * @param parentClass The parent class in which to start looking
	 * @return The type of action to be performed on the given method
	 */
	private MethodWrapType getMethodWrapType(String subSig, SootClass parentClass) {
		// If this is not one of the supported classes, we skip it
		boolean isSupported = false;
		for (String supportedClass : this.includeList)
			if (parentClass.getName().startsWith(supportedClass)) {
				isSupported = true;
				break;
			}
		
		// Do we always model equals() and hashCode()?
		if (alwaysModelEqualsHashCode
				&& (subSig.equals("boolean equals(java.lang.Object)") || subSig.equals("int hashCode()")))
			return MethodWrapType.CreateTaint;
		
		// Do not process unsupported classes
		if (!isSupported)
			return MethodWrapType.NotRegistered;
		
		if (parentClass.isInterface())
			return getInterfaceWrapType(subSig, parentClass);
		else {
			// We have to walk up the hierarchy to also include all methods
			// registered for superclasses
			List<SootClass> superclasses = Scene.v().getActiveHierarchy().getSuperclassesOfIncluding(parentClass);
			for(SootClass sclass : superclasses) {
				MethodWrapType wtClass = getMethodWrapTypeDirect(sclass.getName(), subSig);
				if (wtClass != MethodWrapType.NotRegistered)
					return wtClass;
				
				for (SootClass ifc : sclass.getInterfaces()) {
					MethodWrapType wtIface = getInterfaceWrapType(subSig, ifc);
					if (wtIface != MethodWrapType.NotRegistered)
						return wtIface;
				}
			}
		}
		
		return MethodWrapType.NotRegistered;
	}
	
	/**
	 * Checks whether the taint wrapper has an entry for the given combination
	 * of class/interface and method subsignature. This method does not take the
	 * hierarchy into account.
	 * @param className The name of the class to look for
	 * @param subSignature The method subsignature to look for
	 * @return The type of wrapping if the taint wrapper has been configured
	 * with the given class or interface name and method subsignature, otherwise
	 * NotRegistered.
	 */
	private MethodWrapType getMethodWrapTypeDirect(String className, String subSignature) {
		if (alwaysModelEqualsHashCode
				&& (subSignature.equals("boolean equals(java.lang.Object)") || subSignature.equals("int hashCode()")))
			return MethodWrapType.CreateTaint;

		Set<String> cEntries = classList.get(className);
		Set<String> eEntries = excludeList.get(className);
		Set<String> kEntries = killList.get(className);
		
		if (cEntries != null && cEntries.contains(subSignature))
			return MethodWrapType.CreateTaint;
		if (eEntries != null && eEntries.contains(subSignature))
			return MethodWrapType.Exclude;
		if (kEntries != null && kEntries.contains(subSignature))
			return MethodWrapType.KillTaint;
		return MethodWrapType.NotRegistered;
	}

	/**
	 * Checks whether the taint wrapper has been configured for the given method
	 * in the given interface or one of its parent interfaces. 
	 * @param subSig The method subsignature to look for
	 * @param ifc The interface where to start the search
	 * @return The configured type of wrapping if the given method is implemented
	 * in the given interface or one of its super interfaces, otherwise
	 * NotRegistered
	 */
	private MethodWrapType getInterfaceWrapType(String subSig, SootClass ifc) {
		if (ifc.isPhantom())
			return getMethodWrapTypeDirect(ifc.getName(), subSig);
				
		assert ifc.isInterface() : "Class " + ifc.getName() + " is not an interface, though returned "
				+ "by getInterfaces().";
		for (SootClass pifc : Scene.v().getActiveHierarchy().getSuperinterfacesOfIncluding(ifc)) {
			MethodWrapType wt = getMethodWrapTypeDirect(pifc.getName(), subSig);
			if (wt != MethodWrapType.NotRegistered)
				return wt;
		}
		return MethodWrapType.NotRegistered;
	}
	
	@Override
	public boolean isExclusiveInternal(Stmt stmt, AccessPath taintedPath, IInfoflowCFG icfg) {
		SootMethod method = stmt.getInvokeExpr().getMethod();
		
		// Do we have an entry for at least one entry in the given class?
		if (hasWrappedMethodsForClass(method.getDeclaringClass(), true, true, true))
			return true;

		// In aggressive mode, we always taint the return value if the base
		// object is tainted.
		if (aggressiveMode && stmt.getInvokeExpr() instanceof InstanceInvokeExpr) {
			InstanceInvokeExpr iiExpr = (InstanceInvokeExpr) stmt.getInvokeExpr();			
			if (iiExpr.getBase().equals(taintedPath.getPlainValue()))
				return true;
		}
		
		final MethodWrapType wrapType = methodWrapCache.getUnchecked(method);
		return wrapType != MethodWrapType.NotRegistered;
	}
	
	/**
	 * Sets whether the taint wrapper shall always assume the return value of a
	 * call "a = x.foo()" to be tainted if the base object is tainted, even if
	 * the respective method is not in the data file.
	 * @param aggressiveMode True if return values shall always be tainted if
	 * the base object on which the method is invoked is tainted, otherwise
	 * false
	 */
	public void setAggressiveMode(boolean aggressiveMode) {
		this.aggressiveMode = aggressiveMode;
	}
	
	/**
	 * Gets whether the taint wrapper shall always consider return values as
	 * tainted if the base object of the respective invocation is tainted
	 * @return True if return values shall always be tainted if the base
	 * object on which the method is invoked is tainted, otherwise false
	 */
	public boolean getAggressiveMode() {
		return this.aggressiveMode;
	}
	
	/**
	 * Sets whether the equals() and hashCode() methods shall always be modeled,
	 * regardless of the target type.
	 * @param alwaysModelEqualsHashCode True if the equals() and hashCode()
	 * methods shall always be modeled, regardless of the target type, otherwise
	 * false
	 */
	public void setAlwaysModelEqualsHashCode(boolean alwaysModelEqualsHashCode) {
		this.alwaysModelEqualsHashCode = alwaysModelEqualsHashCode;
	}
	
	/**
	 * Gets whether the equals() and hashCode() methods shall always be modeled,
	 * regardless of the target type.
	 * @return True if the equals() and hashCode() methods shall always be modeled,
	 * regardless of the target type, otherwise false
	 */
	public boolean getAlwaysModelEqualsHashCode() {
		return this.alwaysModelEqualsHashCode;
	}
	
	/**
	 * Registers a prefix of class names to be included when generating taints.
	 * All classes whose names don't start with a registered prefix will be
	 * skipped.
	 * @param prefix The prefix to register
	 */
	public void addIncludePrefix(String prefix) {
		this.includeList.add(prefix);
	}
	
	/**
	 * Adds a method to which the taint wrapping rules shall apply
	 * @param className The class containing the method to be wrapped
	 * @param subSignature The subsignature of the method to be wrapped
	 */
	public void addMethodForWrapping(String className, String subSignature) {
		Set<String> methods = this.classList.get(className);
		if (methods == null) {
			methods = new HashSet<String>();
			this.classList.put(className, methods);
		}
		methods.add(subSignature);
	}
	
	@Override
	public EasyTaintWrapper clone() {
		return new EasyTaintWrapper(this);
	}

	//[start] add by chenxiong
	public Map<String, Set<String>> getClassList() {
		return classList;
	}

	public Map<String, Set<String>> getExcludeList() {
		return excludeList;
	}

	public Map<String, Set<String>> getKillList() {
		return killList;
	}

	public Set<String> getIncludeList() {
		return includeList;
	}
	//[end]
	
	
}
