package com.monster.taint.z3.stmts;

import java.io.PrintWriter;

import soot.Local;
import soot.jimple.StaticFieldRef;

import com.monster.taint.z3.SMT2FileGenerator;
import com.monster.taint.z3.stmts.atom.ASLLocal;
import com.monster.taint.z3.stmts.atom.ASRSFieldRef;

public class AssignStmtLLocalRSFieldRef{
	private PrintWriter writer = null;
	private ASLLocal lLocal = null;
	private ASRSFieldRef rSFieldRef = null;
	
	public AssignStmtLLocalRSFieldRef(PrintWriter writer, SMT2FileGenerator fileGenerator, 
			int stmtIdx, Local lLocal, StaticFieldRef rSFieldRef){
		this.writer = writer;
		this.lLocal = new ASLLocal(writer, fileGenerator, stmtIdx, lLocal);
		this.rSFieldRef = new ASRSFieldRef(writer, fileGenerator, stmtIdx, rSFieldRef);
	}
	
	public void jet(){
		this.lLocal.jet();
		this.rSFieldRef.jet();
		
		writer.println(getAssertStr());
	}

	/**
	 * r = A.f
	 * (assert (= r A_f))
	 * @return
	 */
	private String getAssertStr(){
		StringBuilder sb = new StringBuilder();
		sb.append("(assert (= ");
		sb.append(lLocal.getLLocalName());
		sb.append(" ");
		sb.append(rSFieldRef.getSFieldRefName());
		sb.append("))");
		return sb.toString();
	}
}
