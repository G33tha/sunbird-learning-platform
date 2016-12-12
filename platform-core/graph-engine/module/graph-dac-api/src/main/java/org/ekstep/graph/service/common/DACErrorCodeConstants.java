package org.ekstep.graph.service.common;

public enum DACErrorCodeConstants {
	
	INVALID_POLICY(0, "Invalid Policy Identifier."),
	MISSING_DEFINITION(1, "Missing Definition Node."),
	INVALID_VERSION(2, "Invalid Node Data Version."),
	BLANK_VERSION(3, "Blank Node Data Version Information."),
	STALE_DATA(4, "The Node Contaions/Updated with Stale Data."),
	SERVER_ERROR(5, "The Exception/Error Occured on Server."), 
	INVALID_GRAPH(6, "Invalid Graph Identifier."), 
	INVALID_CONFIG(7, "Invalid Configuration."), 
	INVALID_NODE(8, "Invalid Node Object.");
	
	private final int code;
	private final String description;

	private DACErrorCodeConstants(int code, String description) {
		    this.code = code;
		    this.description = description;
		  }

	public String getDescription() {
		return description;
	}

	public int getCode() {
		return code;
	}

	@Override
	public String toString() {
		return code + ": " + description;
	}

}
