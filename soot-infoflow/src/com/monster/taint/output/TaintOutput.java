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

import com.monster.taint.state.TaintValue;

public class TaintOutput {
	private Logger logger = LoggerFactory.getLogger(getClass());
	private static TaintOutput instance = null;
	
	private TaintOutput(){}
	
	public static TaintOutput v(){
		if(instance == null){
			instance = new TaintOutput();
		}
		return instance;
	}

	/**
	 * Currently, we only care about 'inArgsTVs'
	 * @param inThisTVs
	 * @param inArgsTVs
	 * @param inStaticTVs
	 * @param activationUnit
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
		
		for(int i = 0; i < inArgsTVs.size(); i++){
			ArrayList<TaintValue> argTVs = inArgsTVs.get(i);
			if(argTVs.size() > 0){
				for(int j = 0; j < argTVs.size(); j++){
					TaintValue argTV = argTVs.get(j);
					Element methodElement = getMethodElement(method, activationUnit, argTV, doc, "SinkTV");
					sinkElement.appendChild(methodElement);
				}
			}
		}
		
		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		DOMSource source = new DOMSource(doc);
		StreamResult result = new StreamResult(new File("../monster-out/" + outputFileName));
		transformer.transform(source, result);
	}
	
	private Element getMethodElement(SootMethod method, Unit activationUnit, TaintValue tv, Document doc, String name){
		//method
		Element methodElement = doc.createElement(name);
		methodElement.setAttribute("method-signature", method.getSignature());
		methodElement.setAttribute("activation-stmt", activationUnit.toString());
		methodElement.setAttribute("tainted-value", tv.toString());
					
		//path
		Element pathElement = doc.createElement("path");
		//stms
		ArrayList<Unit> stmts = tv.getFirstContext().getUnitsOnPath();
		for(int stmtIdx = 0; stmtIdx < stmts.size(); stmtIdx++){
			Unit stmt = stmts.get(stmtIdx);
			Element stmtElement = doc.createElement("stmt");
			//stmtElement.appendChild(doc.createTextNode(stmt.toString()));
			stmtElement.setAttribute("value", stmt.toString());
			pathElement.appendChild(stmtElement);
		}
		methodElement.appendChild(pathElement);
					
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
			Element inDepElement = getMethodElement(inDep.getFirstContext().getMethodHub().getMethod(), 
					inDep.getActivationUnit(), inDep, doc, "inDependence");
			propagationElement.appendChild(inDepElement);
		}
		if(tmp.getRetDependence() != null){
			TaintValue retDep = tmp.getRetDependence();
			Element retDepElement = getMethodElement(retDep.getFirstContext().getMethodHub().getMethod(), 
					retDep.getActivationUnit(), retDep, doc, "retDependence");
			propagationElement.appendChild(retDepElement);
		}
		methodElement.appendChild(propagationElement);
		
		return methodElement;
	}
	
}