package com.monster.taint.state;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

import com.monster.taint.path.MethodPath;

import soot.Local;
import soot.SootField;
import soot.Unit;
import soot.Value;
import soot.jimple.ArrayRef;
import soot.jimple.InstanceFieldRef;
import soot.jimple.StaticFieldRef;

public class PathState {
	private ArrayList<TaintValue> taintValues = null;
	private MethodPath methodPath = null;
	
	public PathState(MethodPath methodPath){
		this.taintValues = new ArrayList<TaintValue>();
		this.methodPath = methodPath;
	}

	/**
	 * If the 'taintValue' doesn't exist in current taint
	 * values, add it.
	 * 
	 * @param taintValue
	 * @return : true if added, otherwise, false
	 */
	public boolean addTaintValue(TaintValue taintValue){
		boolean exists = false;
		TaintValue tv = null;
		for(int i = 0; i < this.taintValues.size(); i++){
			tv = this.taintValues.get(i);
			if(tv.equals(taintValue)){
				exists = true;
				break;
			}
		}
		if(!exists){
			this.taintValues.add(taintValue);
		}
		return !exists;
	}
	
	public ArrayList<TaintValue> getTVsBasedOnLocal(Local local){
		ArrayList<TaintValue> retTVs = new ArrayList<TaintValue>();
		for(TaintValue tv : this.taintValues){
			if(tv.getBase() != null && tv.getBase().equals(local) &&
					tv.getBase().getType().equals(local.getType()))
				retTVs.add(tv);
		}
		return retTVs;
	}
	
	public ArrayList<TaintValue> getTVsBasedOnStaticField(SootField sootField){
		ArrayList<TaintValue> retTVs = new ArrayList<TaintValue>();
		for(TaintValue tv : this.taintValues){
			if(tv.isStaticField()){
				if(tv.getAccessPath().get(0).equals(sootField))
					retTVs.add(tv);
			}
		}
		return retTVs;
	}
	
	public ArrayList<TaintValue> getTVsBasedOnInstanceFieldRef(InstanceFieldRef ifr){
		ArrayList<TaintValue> retTVs = new ArrayList<TaintValue>();
		Value ifrBase = ifr.getBase();
		SootField ifrField = ifr.getField();
		for(TaintValue tv : this.taintValues){
			if(tv.getBase() != null && tv.getBase().equals(ifrBase) &&
					tv.getBase().getType().equals(ifrBase.getType())){
				if(tv.getAccessPath().size() > 0 && tv.getAccessPath().get(0).equals(ifrField))
					retTVs.add(tv);
			}
		}
		return retTVs;
	}

	/**
	 * Erase taint values related to value
	 * 
	 * @param value : Local, InstanceFieldRef, StaticFieldRef, array
	 * @param currUnit
	 */
	public void eraseTaintOf(Value value, Unit currUnit){
		int currIndex = this.methodPath.getUnitsOnPath().indexOf(currUnit);
		ArrayList<TaintValue> tvs = null;
		if(value instanceof Local){
			Local local = (Local) value;
			tvs = getTVsBasedOnLocal(local);
		}else if(value instanceof InstanceFieldRef){
			InstanceFieldRef ifr = (InstanceFieldRef) value;
			tvs = getTVsBasedOnInstanceFieldRef(ifr);
		}else if(value instanceof StaticFieldRef){
			StaticFieldRef sfr = (StaticFieldRef) value;
			tvs = getTVsBasedOnStaticField(sfr.getField());
		}else if(value instanceof ArrayRef){
			ArrayRef ar = (ArrayRef) value;
			tvs = getTVsBasedOnLocal((Local) ar.getBase());
		}
		assert(tvs != null);
		for(TaintValue tv : tvs){
			int tvIndex = this.methodPath.getUnitsOnPath().indexOf(tv.getActivationUnit());
			if(currIndex > tvIndex){
				deleteTaintValue(tv);
			}
		}
	}
	
	public void deleteTaintValue(TaintValue tv){
		//delete tv's slaves
		Set<TaintValue> slaves = tv.getSlaves();
		for(TaintValue slave : slaves){
			deleteTaintValue(slave);
		}
		//delete tv
		Iterator<TaintValue> iter = this.taintValues.iterator();
		while(iter.hasNext()){
			if(iter.next() == tv){
				iter.remove();
				break;
			}
		}
	}
	
	public ArrayList<TaintValue> getStaticTVs(){
		ArrayList<TaintValue> retTVs = new ArrayList<TaintValue>();
		for(TaintValue tv : this.taintValues){
			if(tv.isStaticField())
				retTVs.add(tv);
		}
		return retTVs;
	}
	
	public MethodPath getMethodPath(){
		return this.methodPath;
	}

	private static boolean accessPathEquals(ArrayList<SootField> path1, ArrayList<SootField> path2){
		if(path1.size() != path2.size())
			return false;
		for(int i = 0; i < path1.size(); i++){
			if(!path1.get(i).toString().equals(path2.get(i).toString()))
				return false;
		}
		return true;
	}
	
	private static boolean containsAccessPath(ArrayList<TaintValue> oldTVs, 
			ArrayList<SootField> accesPath){
		boolean contains = false;
		for(TaintValue oldTV : oldTVs){
			ArrayList<SootField> oldAccessPath = oldTV.getAccessPath();
			if(accessPathEquals(oldAccessPath, accesPath)){
				contains = true;
				break;
			}
		}
		return contains;
		
	}
	
	public static ArrayList<TaintValue> getNewProducedTVs(ArrayList<TaintValue> oldTVs, 
			ArrayList<TaintValue> retTVs){
		ArrayList<TaintValue> newProducedTVs = new ArrayList<TaintValue>();
		for(TaintValue retTV : retTVs){
			if(!containsAccessPath(oldTVs, retTV.getAccessPath()))
				newProducedTVs.add(retTV);
		}
		return newProducedTVs;
	}
	
	public static ArrayList<TaintValue> getUntaintedNonStaticTVs(ArrayList<TaintValue> oldTVs,
			ArrayList<TaintValue> retTVs){
		return getNewProducedTVs(retTVs, oldTVs);
	}
	
}
