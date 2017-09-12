package org.ekstep.graph.service.operation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;
import org.ekstep.graph.service.common.CypherQueryConfigurationConstants;
import org.ekstep.graph.service.common.DACConfigurationConstants;
import org.ekstep.graph.service.common.DACErrorCodeConstants;
import org.ekstep.graph.service.common.DACErrorMessageConstants;
import org.ekstep.graph.service.common.GraphOperation;
import org.ekstep.graph.service.common.Neo4JOperation;
import org.ekstep.graph.service.request.validator.Neo4JBoltAuthorizationValidator;
import org.ekstep.graph.service.request.validator.Neo4jBoltValidator;
import org.ekstep.graph.service.util.DriverUtil;
import org.ekstep.graph.service.util.QueryUtil;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Value;

import com.ilimi.common.dto.Property;
import com.ilimi.common.dto.Request;
import com.ilimi.common.exception.ClientException;
import com.ilimi.common.exception.ResourceNotFoundException;
import com.ilimi.common.exception.ServerException;
import com.ilimi.common.logger.PlatformLogger;
import com.ilimi.graph.cache.mgr.impl.NodeCacheManager;
import com.ilimi.graph.cache.util.RedisStoreUtil;
import com.ilimi.graph.common.DateUtils;
import com.ilimi.graph.common.Identifier;
import com.ilimi.graph.dac.enums.AuditProperties;
import com.ilimi.graph.dac.enums.GraphDACParams;
import com.ilimi.graph.dac.enums.SystemNodeTypes;
import com.ilimi.graph.dac.enums.SystemProperties;
import com.ilimi.graph.dac.model.Node;

public class Neo4JBoltNodeOperations {

	private final static String DEFAULT_CYPHER_NODE_OBJECT = "ee";
	private Neo4jBoltValidator versionValidator = new Neo4jBoltValidator();
	private Neo4JBoltAuthorizationValidator authorizationValidator = new Neo4JBoltAuthorizationValidator();

	@SuppressWarnings("unchecked")
	public com.ilimi.graph.dac.model.Node upsertNode(String graphId, com.ilimi.graph.dac.model.Node node,
			Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Graph Engine Node: ", node);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Upsert Node Operation Failed.]");

		if (null == node)
			throw new ClientException(DACErrorCodeConstants.INVALID_NODE.name(),
					DACErrorMessageConstants.INVALID_NODE + " | [Upsert Node Operation Failed.]");

		PlatformLogger.log("Applying the Consumer Authorization Check for Node Id: " + node.getIdentifier());
		node.getMetadata().put(GraphDACParams.channel.name(), getChannel(request));
		node.getMetadata().put(GraphDACParams.consumerId.name(), getConsumerId(request));
		node.getMetadata().put(GraphDACParams.appId.name(), getAppId(request));
		authorizationValidator.validateAuthorization(graphId, node, request);
		PlatformLogger.log("Consumer is Authorized for Node Id: " + node.getIdentifier());

		PlatformLogger.log("Validating the Update Operation for Node Id: " + node.getIdentifier());
		versionValidator.validateUpdateOperation(graphId, node);
		node.getMetadata().remove(GraphDACParams.versionKey.name());
		PlatformLogger.log("Node Update Operation has been Validated for Node Id: " + node.getIdentifier());

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");
			node.setGraphId(graphId);

			PlatformLogger.log("Populating Parameter Map.");
			Map<String, Object> parameterMap = new HashMap<String, Object>();
			parameterMap.put(GraphDACParams.graphId.name(), graphId);
			parameterMap.put(GraphDACParams.node.name(), node);
			parameterMap.put(GraphDACParams.request.name(), request);

			QueryUtil.getQuery(Neo4JOperation.UPSERT_NODE, parameterMap);
			Map<String, Object> queryMap = (Map<String, Object>) parameterMap
					.get(GraphDACParams.queryStatementMap.name());
			for (Entry<String, Object> entry : queryMap.entrySet()) {
				String statementTemplate = StringUtils.removeEnd(
						(String) ((Map<String, Object>) entry.getValue()).get(GraphDACParams.query.name()),
						CypherQueryConfigurationConstants.COMMA);
				Map<String, Object> statementParameters = (Map<String, Object>) ((Map<String, Object>) entry.getValue())
						.get(GraphDACParams.paramValueMap.name());
				StatementResult result = session.run(statementTemplate, statementParameters);
				for (Record record : result.list()) {
					try {
						org.neo4j.driver.v1.types.Node neo4JNode = record.get(DEFAULT_CYPHER_NODE_OBJECT).asNode();
						String versionKey = (String) neo4JNode.get(GraphDACParams.versionKey.name()).asString();
						String identifier = (String) neo4JNode.get(SystemProperties.IL_UNIQUE_ID.name()).asString();
						node.setIdentifier(identifier);
						if (StringUtils.isNotBlank(versionKey))
							node.getMetadata().put(GraphDACParams.versionKey.name(), versionKey);
						try {
							updateRedisCache(graphId, neo4JNode, node.getIdentifier(), node.getNodeType());
						} catch (Exception e) {
							PlatformLogger.log("Error! While updating redis cache From Neo4J Node.", null, e);
							throw new ServerException(DACErrorCodeConstants.CACHE_ERROR.name(),
									DACErrorMessageConstants.CACHE_ERROR + " | " + e.getMessage());
						}
						PlatformLogger.log("Bolt Neo4J Node: ", neo4JNode);
					} catch (Exception e) {
						PlatformLogger.log("Error! While Fetching 'versionKey' From Neo4J Node.", null, e);
					}
				}
			}
		}
		return node;
	}

	@SuppressWarnings("unchecked")
	public com.ilimi.graph.dac.model.Node addNode(String graphId, com.ilimi.graph.dac.model.Node node,
			Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Graph Engine Node: ", node);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Create Node Operation Failed.]");

		if (null == node)
			throw new ClientException(DACErrorCodeConstants.INVALID_NODE.name(),
					DACErrorMessageConstants.INVALID_NODE + " | [Create Node Operation Failed.]");

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");
			node.setGraphId(graphId);

			PlatformLogger.log("Adding Authorization Metadata.");
			node.getMetadata().put(GraphDACParams.consumerId.name(), getConsumerId(request));
			node.getMetadata().put(GraphDACParams.channel.name(), getChannel(request));
			node.getMetadata().put(GraphDACParams.appId.name(), getAppId(request));

			PlatformLogger.log("Populating Parameter Map.");
			Map<String, Object> parameterMap = new HashMap<String, Object>();
			parameterMap.put(GraphDACParams.graphId.name(), graphId);
			parameterMap.put(GraphDACParams.node.name(), node);
			parameterMap.put(GraphDACParams.request.name(), request);

			QueryUtil.getQuery(Neo4JOperation.CREATE_NODE, parameterMap);

			Map<String, Object> queryMap = (Map<String, Object>) parameterMap
					.get(GraphDACParams.queryStatementMap.name());
			for (Entry<String, Object> entry : queryMap.entrySet()) {
				String statementTemplate = StringUtils.removeEnd(
						(String) ((Map<String, Object>) entry.getValue()).get(GraphDACParams.query.name()),
						CypherQueryConfigurationConstants.COMMA);
				Map<String, Object> statementParameters = (Map<String, Object>) ((Map<String, Object>) entry.getValue())
						.get(GraphDACParams.paramValueMap.name());
				try {
					StatementResult result = session.run(statementTemplate, statementParameters);
					for (Record record : result.list()) {
						try {
							org.neo4j.driver.v1.types.Node neo4JNode = record.get(DEFAULT_CYPHER_NODE_OBJECT).asNode();
							String versionKey = (String) neo4JNode.get(GraphDACParams.versionKey.name()).asString();
							String identifier = (String) neo4JNode.get(SystemProperties.IL_UNIQUE_ID.name()).asString();
							node.setIdentifier(identifier);
							if (StringUtils.isNotBlank(versionKey))
								node.getMetadata().put(GraphDACParams.versionKey.name(), versionKey);
							try {
								updateRedisCache(graphId, neo4JNode, node.getIdentifier(), node.getNodeType());
							} catch (Exception e) {
								PlatformLogger.log("Error! While updating redis cache From Neo4J Node.", null, e);
								throw new ServerException(DACErrorCodeConstants.CACHE_ERROR.name(),
										DACErrorMessageConstants.CACHE_ERROR + " | " + e.getMessage());
							}
							PlatformLogger.log("Bolt Neo4J Node: ", neo4JNode);
						} catch (Exception e) {
							PlatformLogger.log("Error! While Fetching 'versionKey' From Neo4J Node.", null, e);
						}
					}
				} catch (org.neo4j.driver.v1.exceptions.ClientException e) {
					if (StringUtils.equalsIgnoreCase("Neo.ClientError.Schema.ConstraintValidationFailed", e.code()))
						throw new ClientException("ConstraintValidationFailed",
								"Object already exists with identifier: " + node.getIdentifier());
					else
						throw new ServerException(e.code(), e.getMessage());
				}
			}
		}
		return node;
	}

	@SuppressWarnings("unchecked")
	public com.ilimi.graph.dac.model.Node updateNode(String graphId, com.ilimi.graph.dac.model.Node node,
			Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Graph Engine Node: ", node);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Update Node Operation Failed.]");
		if (null == node)
			throw new ClientException(DACErrorCodeConstants.INVALID_NODE.name(),
					DACErrorMessageConstants.INVALID_NODE + " | [Update Node Operation Failed.]");

		PlatformLogger.log("Applying the Consumer Authorization Check for Node Id: " + node.getIdentifier());
		authorizationValidator.validateAuthorization(graphId, node, request);
		node.getMetadata().remove(GraphDACParams.consumerId.name());
		PlatformLogger.log("Consumer is Authorized for Node Id: " + node.getIdentifier());

		PlatformLogger.log("Validating the Update Operation for Node Id: " + node.getIdentifier());
		PlatformLogger.log("Validating the Update Operation for Node Id: " + node.getIdentifier());
		versionValidator.validateUpdateOperation(graphId, node);
		node.getMetadata().remove(GraphDACParams.versionKey.name());
		PlatformLogger.log("Node Update Operation has been Validated for Node Id: " + node.getIdentifier());
		
		// Adding Channel and App Id
		node.getMetadata().put(GraphDACParams.channel.name(), getChannel(request));
		node.getMetadata().put(GraphDACParams.appId.name(), getAppId(request));

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");

			PlatformLogger.log("Populating Parameter Map.");
			Map<String, Object> parameterMap = new HashMap<String, Object>();
			parameterMap.put(GraphDACParams.graphId.name(), graphId);
			parameterMap.put(GraphDACParams.node.name(), node);
			parameterMap.put(GraphDACParams.request.name(), request);

			QueryUtil.getQuery(Neo4JOperation.UPDATE_NODE, parameterMap);
			Map<String, Object> queryMap = (Map<String, Object>) parameterMap
					.get(GraphDACParams.queryStatementMap.name());
			for (Entry<String, Object> entry : queryMap.entrySet()) {
				String statementTemplate = StringUtils.removeEnd(
						(String) ((Map<String, Object>) entry.getValue()).get(GraphDACParams.query.name()),
						CypherQueryConfigurationConstants.COMMA);
				Map<String, Object> statementParameters = (Map<String, Object>) ((Map<String, Object>) entry.getValue())
						.get(GraphDACParams.paramValueMap.name());
				StatementResult result = session.run(statementTemplate, statementParameters);
				if (null == result || !result.hasNext())
					throw new ResourceNotFoundException(DACErrorCodeConstants.NOT_FOUND.name(),
							DACErrorMessageConstants.NODE_NOT_FOUND + " | " + node.getIdentifier());
				for (Record record : result.list()) {
					try {
						org.neo4j.driver.v1.types.Node neo4JNode = record.get(DEFAULT_CYPHER_NODE_OBJECT).asNode();
						String versionKey = (String) neo4JNode.get(GraphDACParams.versionKey.name()).asString();
						String identifier = (String) neo4JNode.get(SystemProperties.IL_UNIQUE_ID.name()).asString();
						node.setIdentifier(identifier);
						if (StringUtils.isNotBlank(versionKey))
							node.getMetadata().put(GraphDACParams.versionKey.name(), versionKey);
						try {
							updateRedisCache(graphId, neo4JNode, node.getIdentifier(), node.getNodeType());
						} catch (Exception e) {
							PlatformLogger.log("Error! While updating redis cache From Neo4J Node.", null, e);
							throw new ServerException(DACErrorCodeConstants.CACHE_ERROR.name(),
									DACErrorMessageConstants.CACHE_ERROR + " | " + e.getMessage());
						}
						PlatformLogger.log("Bolt Neo4J Node: ", neo4JNode);
					} catch (Exception e) {
						PlatformLogger.log("Error! While Fetching 'versionKey' From Neo4J Node.", null, e);
					}
				}
			}

		}
		return node;
	}

	public void importNodes(String graphId, List<com.ilimi.graph.dac.model.Node> nodes, Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Graph Engine Node List: ", nodes);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Import Nodes Operation Failed.]");
		if (null == nodes || nodes.size() <= 0)
			throw new ClientException(DACErrorCodeConstants.INVALID_NODE.name(),
					DACErrorMessageConstants.INVALID_NODE_LIST + " | [Import Nodes Operation Failed.]");
		for (com.ilimi.graph.dac.model.Node node : nodes) {
			if (null == node.getMetadata())
				node.setMetadata(new HashMap<String, Object>());
			node.getMetadata().put(SystemProperties.IL_UNIQUE_ID.name(), node.getIdentifier());
			node.getMetadata().put(SystemProperties.IL_SYS_NODE_TYPE.name(), node.getNodeType());
			if (StringUtils.isNotBlank(node.getObjectType()))
				node.getMetadata().put(SystemProperties.IL_FUNC_OBJECT_TYPE.name(), node.getObjectType());
			upsertNode(graphId, node, request);
		}
	}

	public void updatePropertyValue(String graphId, String nodeId, Property property, Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Start Node Id: ", nodeId);
		PlatformLogger.log("Property: ", property);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Update Property Value Operation Failed.]");

		if (StringUtils.isBlank(nodeId))
			throw new ClientException(DACErrorCodeConstants.INVALID_IDENTIFIER.name(),
					DACErrorMessageConstants.INVALID_IDENTIFIER + " | [Update Property Value Operation Failed.]");

		if (null == property)
			throw new ClientException(DACErrorCodeConstants.INVALID_PROPERTY.name(),
					DACErrorMessageConstants.INVALID_PROPERTY + " | [Update Property Value Operation Failed.]");

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");
			String date = DateUtils.formatCurrentDate();
			Node node = new Node();
			node.setGraphId(graphId);
			node.setIdentifier(nodeId);
			node.setMetadata(new HashMap<String, Object>());
			node.getMetadata().put(property.getPropertyName(), property.getPropertyValue());
			node.getMetadata().put(AuditProperties.lastUpdatedOn.name(), date);
			if (!StringUtils.isBlank(date))
				node.getMetadata().put(GraphDACParams.versionKey.name(),
						Long.toString(DateUtils.parse(date).getTime()));
			updateNode(graphId, node, request);
		}
	}

	public void updatePropertyValues(String graphId, String nodeId, Map<String, Object> metadata, Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Start Node Id: ", nodeId);
		PlatformLogger.log("Properties (Metadata): ", metadata);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Update Property Value Operation Failed.]");

		if (StringUtils.isBlank(nodeId))
			throw new ClientException(DACErrorCodeConstants.INVALID_IDENTIFIER.name(),
					DACErrorMessageConstants.INVALID_IDENTIFIER + " | [Update Property Value Operation Failed.]");

		if (null == metadata)
			throw new ClientException(DACErrorCodeConstants.INVALID_PROPERTY.name(),
					DACErrorMessageConstants.INVALID_PROPERTY + " | [Update Property Value Operation Failed.]");

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");

			String date = DateUtils.formatCurrentDate();
			Node node = new Node();
			node.setGraphId(graphId);
			node.setIdentifier(nodeId);
			node.setMetadata(new HashMap<String, Object>());
			node.getMetadata().putAll(metadata);
			node.getMetadata().put(AuditProperties.lastUpdatedOn.name(), date);
			if (!StringUtils.isBlank(date))
				node.getMetadata().put(GraphDACParams.versionKey.name(),
						Long.toString(DateUtils.parse(date).getTime()));
			updateNode(graphId, node, request);
		}
	}

	public void removePropertyValue(String graphId, String nodeId, String key, Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Start Node Id: ", nodeId);
		PlatformLogger.log("Property (Key to Remove): ", key);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Remove Property Value Operation Failed.]");

		if (StringUtils.isBlank(nodeId))
			throw new ClientException(DACErrorCodeConstants.INVALID_IDENTIFIER.name(),
					DACErrorMessageConstants.INVALID_IDENTIFIER + " | [Remove Property Value Operation Failed.]");

		if (StringUtils.isBlank(key))
			throw new ClientException(DACErrorCodeConstants.INVALID_PROPERTY.name(),
					DACErrorMessageConstants.INVALID_PROPERTY + " | [Remove Property Value Operation Failed.]");

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");

			PlatformLogger.log("Populating Parameter Map.");
			Map<String, Object> parameterMap = new HashMap<String, Object>();
			parameterMap.put(GraphDACParams.graphId.name(), graphId);
			parameterMap.put(GraphDACParams.nodeId.name(), nodeId);
			parameterMap.put(GraphDACParams.key.name(), key);
			parameterMap.put(GraphDACParams.request.name(), request);

			StatementResult result = session.run(QueryUtil.getQuery(Neo4JOperation.REMOVE_PROPERTY, parameterMap));
			for (Record record : result.list())
				PlatformLogger.log("Remove Property Value Operation | ", record);
			
			NodeCacheManager.deleteDataNode(graphId, nodeId);
		}
	}

	public void removePropertyValues(String graphId, String nodeId, List<String> keys, Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Start Node Id: ", nodeId);
		PlatformLogger.log("Property (Keys to Remove): ", keys);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Remove Property Values Operation Failed.]");

		if (StringUtils.isBlank(nodeId))
			throw new ClientException(DACErrorCodeConstants.INVALID_IDENTIFIER.name(),
					DACErrorMessageConstants.INVALID_IDENTIFIER + " | [Remove Property Values Operation Failed.]");

		if (null == keys || keys.size() <= 0)
			throw new ClientException(DACErrorCodeConstants.INVALID_PROPERTY.name(),
					DACErrorMessageConstants.INVALID_PROPERTY + " | [Remove Property Values Operation Failed.]");

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");

			PlatformLogger.log("Populating Parameter Map.");
			Map<String, Object> parameterMap = new HashMap<String, Object>();
			parameterMap.put(GraphDACParams.graphId.name(), graphId);
			parameterMap.put(GraphDACParams.nodeId.name(), nodeId);
			parameterMap.put(GraphDACParams.keys.name(), keys);
			parameterMap.put(GraphDACParams.request.name(), request);

			StatementResult result = session.run(QueryUtil.getQuery(Neo4JOperation.REMOVE_PROPERTIES, parameterMap));
			for (Record record : result.list())
				PlatformLogger.log("Update Property Values Operation | ", record);

			NodeCacheManager.deleteDataNode(graphId, nodeId);
		}
	}

	public void deleteNode(String graphId, String nodeId, Request request) {
		PlatformLogger.log("Graph Id: ", graphId);
		PlatformLogger.log("Start Node Id: ", nodeId);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Remove Property Values Operation Failed.]");

		if (StringUtils.isBlank(nodeId))
			throw new ClientException(DACErrorCodeConstants.INVALID_IDENTIFIER.name(),
					DACErrorMessageConstants.INVALID_IDENTIFIER + " | [Remove Property Values Operation Failed.]");

		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");

			PlatformLogger.log("Populating Parameter Map.");
			Map<String, Object> parameterMap = new HashMap<String, Object>();
			parameterMap.put(GraphDACParams.graphId.name(), graphId);
			parameterMap.put(GraphDACParams.nodeId.name(), nodeId);
			parameterMap.put(GraphDACParams.request.name(), request);

			StatementResult result = session.run(QueryUtil.getQuery(Neo4JOperation.DELETE_NODE, parameterMap));
			for (Record record : result.list())
				PlatformLogger.log("Delete Node Operation | ", record);

			NodeCacheManager.deleteDataNode(graphId, nodeId);
			try {
				RedisStoreUtil.deleteNodeProperties(graphId, nodeId);
			} catch (Exception e) {
				PlatformLogger.log("Error! While deleting redis cache From Neo4J Node.", null, e);
			}

		}
	}

	public com.ilimi.graph.dac.model.Node upsertRootNode(String graphId, Request request) {
		PlatformLogger.log("Graph Id: ", graphId);

		if (StringUtils.isBlank(graphId))
			throw new ClientException(DACErrorCodeConstants.INVALID_GRAPH.name(),
					DACErrorMessageConstants.INVALID_GRAPH_ID + " | [Upsert Root Node Operation Failed.]");

		PlatformLogger.log("Initializing Node.");
		Node node = new Node();
		node.setMetadata(new HashMap<String, Object>());
		Driver driver = DriverUtil.getDriver(graphId, GraphOperation.WRITE);
		PlatformLogger.log("Driver Initialised. | [Graph Id: " + graphId + "]");
		try (Session session = driver.session()) {
			PlatformLogger.log("Session Initialised. | [Graph Id: " + graphId + "]");

			// Generating Root Node Id
			String rootNodeUniqueId = Identifier.getIdentifier(graphId, SystemNodeTypes.ROOT_NODE.name());
			PlatformLogger.log("Generated Root Node Id: " + rootNodeUniqueId);

			PlatformLogger.log("Adding Metadata to Node.");
			node.setGraphId(graphId);
			node.setNodeType(SystemNodeTypes.ROOT_NODE.name());
			node.setIdentifier(rootNodeUniqueId);
			node.getMetadata().put(SystemProperties.IL_UNIQUE_ID.name(), rootNodeUniqueId);
			node.getMetadata().put(SystemProperties.IL_SYS_NODE_TYPE.name(), SystemNodeTypes.ROOT_NODE.name());
			node.getMetadata().put(AuditProperties.createdOn.name(), DateUtils.formatCurrentDate());
			node.getMetadata().put(GraphDACParams.nodesCount.name(), 0);
			node.getMetadata().put(GraphDACParams.relationsCount.name(), 0);
			PlatformLogger.log("Root Node Initialized.", node);

			PlatformLogger.log("Populating Parameter Map.");
			Map<String, Object> parameterMap = new HashMap<String, Object>();
			parameterMap.put(GraphDACParams.graphId.name(), graphId);
			parameterMap.put(GraphDACParams.rootNode.name(), node);
			parameterMap.put(GraphDACParams.request.name(), request);

			StatementResult result = session.run(QueryUtil.getQuery(Neo4JOperation.UPSERT_ROOT_NODE, parameterMap));
			for (Record record : result.list())
				PlatformLogger.log("Upsert Root Node Operation | ", record);
		}
		return node;
	}

	static Object convert(Value value) {
		switch (value.type().name()) {
		case "PATH":
			return value.asList(Neo4JBoltNodeOperations::convert);
		case "NODE":
			return value.asNode();
		case "RELATIONSHIP":
			return value.asRelationship();
		}
		return value.asObject();
	}

	private String getConsumerId(Request request) {
		String consumerId = "";
		try {
			if (null != request && null != request.getContext()) {
				consumerId = (String) request.getContext().get(GraphDACParams.CONSUMER_ID.name());
			}
		} catch (Exception e) {
			PlatformLogger.log("Error! While Fetching the Consumer Id From Request.", e.getMessage(), e);
		}
		return consumerId;
	}

	private String getChannel(Request request) {
		String channelId = "";
		try {
			if (null != request && null != request.getContext()) {
				channelId = (String) request.getContext().get(GraphDACParams.CHANNEL_ID.name());
				if (StringUtils.isBlank(channelId))
					channelId = DACConfigurationConstants.DEFAULT_CHANNEL_ID;
			}
		} catch (Exception e) {
			PlatformLogger.log("Error! While Fetching the Channel Id From Request.", e.getMessage(), e);
		}
		return channelId;
	}
	
	private String getAppId(Request request) {
		String appId = "";
		try {
			if (null != request && null != request.getContext()) {
				appId = (String) request.getContext().get(GraphDACParams.APP_ID.name());
			}
		} catch (Exception e) {
			PlatformLogger.log("Error! While Fetching the App Id From Request.", e.getMessage(), e);
		}
		return appId;
	}

	private void updateRedisCache(String graphId, org.neo4j.driver.v1.types.Node neo4JNode, String nodeId, String nodeType) {

		if(!graphId.equalsIgnoreCase("domain"))
			return;

		if (!nodeType.equalsIgnoreCase(SystemNodeTypes.DATA_NODE.name()))
			return;

		Map<String, Object> cacheMap = new HashMap<>();
		if (StringUtils.isNotBlank(neo4JNode.get(GraphDACParams.versionKey.name()).asString()))
			cacheMap.put(GraphDACParams.versionKey.name(), neo4JNode.get(GraphDACParams.versionKey.name()).asString());
		if (StringUtils.isNotBlank(neo4JNode.get(GraphDACParams.consumerId.name()).asString()))
			cacheMap.put(GraphDACParams.consumerId.name(), neo4JNode.get(GraphDACParams.consumerId.name()).asString());
		if (StringUtils.isNotBlank(neo4JNode.get(GraphDACParams.lastUpdatedOn.name()).asString()))
			cacheMap.put(GraphDACParams.lastUpdatedOn.name(), neo4JNode.get(GraphDACParams.lastUpdatedOn.name()).asString());
		if (StringUtils.isNotBlank(neo4JNode.get(GraphDACParams.createdBy.name()).asString()))
			cacheMap.put(GraphDACParams.createdBy.name(), neo4JNode.get(GraphDACParams.createdBy.name()).asString());
		if (StringUtils.isNotBlank(neo4JNode.get(GraphDACParams.status.name()).asString()))
			cacheMap.put(GraphDACParams.status.name(), neo4JNode.get(GraphDACParams.status.name()).asString());

		if (cacheMap.size() > 0)
			RedisStoreUtil.saveNodeProperties(graphId, nodeId, cacheMap);
		NodeCacheManager.deleteDataNode(graphId, nodeId);
	}
}
