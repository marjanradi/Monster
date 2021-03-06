package com.monster.taint.output;

import java.io.File;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import soot.SootMethod;
import soot.Unit;

import com.monster.taint.path.MethodPath;
import com.monster.taint.state.TaintValue;

public class TaintOutput {
	private Logger logger = LoggerFactory.getLogger(getClass());
	private static TaintOutput instance = null;
	private final String PATH_DIR = "../monster-out/paths/";
	
	private TaintOutput(){}
	
	public static TaintOutput v(){
		if(instance == null){
			instance = new TaintOutput();
		}
		return instance;
	}

	/**
	 * When we meet a sink with taint values, we collect the taint propagation
	 * path.
	 * 
	 * @param inThisTVs : taint values of 'this'
	 * @param inArgsTVs : args's taint values
	 * @param inStaticTVs : static fields' taint values
	 * @param activationUnit : the stmt invoking the sink
	 * @param method : the method contains 'activationUnit'
	 * @param pathID : ID of this path of method
	 * @throws ParserConfigurationException 
	 */
	public void collectTaint(ArrayList<TaintValue> inThisTVs, 
			ArrayList<ArrayList<TaintValue>> inArgsTVs, ArrayList<TaintValue> inStaticTVs,
			Unit activationUnit, SootMethod method, int pathID) throws Exception{
	
		String outputFileName = method.getDeclaringClass().getName() + "-" + method.getName() + "-" + pathID + ".xml";
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
		Document doc = docBuilder.newDocument();
		
		Element sinkElement = doc.createElement("Sink");
		doc.appendChild(sinkElement);
	
		//currently, we only care about the arguments' taint values
		for(int i = 0; i < inArgsTVs.size(); i++){
			ArrayList<TaintValue> argTVs = inArgsTVs.get(i);
			if(argTVs.size() > 0){
				for(int j = 0; j < argTVs.size(); j++){
					TaintValue argTV = argTVs.get(j);
					//not initialized yet
					PathChain pathChain = new PathChain();
					Element methodElement = getMethodElement(method, activationUnit, argTV, doc,
							"SinkTV", pathChain);
					
					//handle path chain, do ITE and IntentSource slices 
					PathOutput.v().handlePathChain(pathChain, doc, methodElement);
					sinkElement.appendChild(methodElement);
					
					//extract the constraints
					ConstraintOutput.v().extractConstraints(pathChain);
				}
			}
		}
		
		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		DOMSource source = new DOMSource(doc);
		StreamResult result = new StreamResult(new File(PATH_DIR + outputFileName));
		transformer.transform(source, result);
	}

	/**
	 * 
	 * @param method : method contains activationUnit
	 * @param activationUnit : the stmt invoking the Sink
	 * @param tv : the tainted param
	 * @param doc
	 * @param name : "SinkTV", "inDependence", "retDependence"
	 * @param pathChain : the path of method contains activationUnit
	 * @return
	 */
	private Element getMethodElement(SootMethod method, Unit activationUnit, TaintValue tv, 
			Document doc, String name, PathChain pathChain){
		//method
		Element methodElement = doc.createElement(name);
		methodElement.setAttribute("method-signature", method.getSignature());
		methodElement.setAttribute("activation-stmt", activationUnit.toString());
		methodElement.setAttribute("tainted-value", tv.toString());
		
		//for the inDependence or retDependence, there maybe more than
		//one patt, paths elements
		Element pathsElement = doc.createElement("paths");

		/*
		 * methodPaths'size may larger than 1 if 'tv' name is
		 * 'inDependence' or 'retDependence'
		 */
		ArrayList<MethodPath> methodPaths = tv.getContexts();
		//if this is the root pathchain, init it
		if("SinkTV".equals(name)){
			pathChain.init(methodPaths.get(0), activationUnit);
		}
		for(MethodPath methodPath : methodPaths){
			//path
			Element pathElement = doc.createElement("path");
			//stms
			ArrayList<Unit> stmts = methodPath.getUnitsOnPath();
			pathElement.setAttribute("length", "" + stmts.size());
			for(int stmtIdx = 0; stmtIdx < stmts.size(); stmtIdx++){
				Unit stmt = stmts.get(stmtIdx);
				Element stmtElement = doc.createElement("stmt");
				//stmtElement.appendChild(doc.createTextNode(stmt.toString()));
				stmtElement.setAttribute("value", stmt.toString());
				pathElement.appendChild(stmtElement);
			}
			pathsElement.appendChild(pathElement);
		}
		methodElement.appendChild(pathsElement);
					
		//taint propagation
		Element propagationElement = doc.createElement("taint-propagation");
		//tv
		Element tvElement = doc.createElement("taint-value");
		tvElement.setAttribute("value", tv.toString());
		tvElement.setAttribute("activationStmt", tv.getActivationUnit().toString());
		propagationElement.appendChild(tvElement);
		TaintValue tmp = tv;
		while(tmp.getDependence() != null){
			//dependence
			TaintValue dependence = tmp.getDependence();
			Element depElement = doc.createElement("dependence");
			depElement.setAttribute("value", dependence.toString());
			depElement.setAttribute("activationStmt", dependence.getActivationUnit().toString());
			propagationElement.appendChild(depElement);
			tmp = dependence;
		}
		if(tmp.getInDependence() != null){
			TaintValue inDep = tmp.getInDependence();
			//set inDeps
			pathChain.setInDepPaths(inDep.getContexts());
			Element inDepElement = getMethodElement(inDep.getFirstContext().getMethodHub().getMethod(), 
					inDep.getActivationUnit(), inDep, doc, "inDependence", pathChain.getFirstInDepChain());
			propagationElement.appendChild(inDepElement);
		}
		if(tmp.getRetDependence() != null){
			TaintValue retDep = tmp.getRetDependence();
			//set retDeps
			pathChain.setRetDepPaths(retDep.getContexts());
			Element retDepElement = getMethodElement(retDep.getFirstContext().getMethodHub().getMethod(), 
					retDep.getActivationUnit(), retDep, doc, "retDependence", pathChain.getFirstRetDepChain());
			propagationElement.appendChild(retDepElement);
		}
		methodElement.appendChild(propagationElement);
		
		return methodElement;
	}
	
}
