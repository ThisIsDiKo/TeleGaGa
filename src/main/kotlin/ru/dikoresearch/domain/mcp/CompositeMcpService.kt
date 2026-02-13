package ru.dikoresearch.domain.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Composite MCP service that aggregates multiple MCP services
 * Implements the Composite pattern to treat multiple MCP services as one
 */
class CompositeMcpService(
    private val services: List<McpService>
) : McpService {
    private val mutex = Mutex()
    private val toolToServiceMap = mutableMapOf<String, McpService>()

    override suspend fun initialize() {
        services.forEach { service ->
            service.initialize()
        }
        // Build tool-to-service mapping
        rebuildToolMapping()
    }

    override suspend fun listTools(): List<McpTool> = mutex.withLock {
        services.flatMap { service ->
            if (service.isAvailable()) {
                service.listTools()
            } else {
                emptyList()
            }
        }
    }

    override suspend fun callTool(name: String, args: Map<String, Any>): McpCallToolResponse {
        val service = toolToServiceMap[name]
            ?: throw IllegalArgumentException("Tool not found: $name")

        return service.callTool(name, args)
    }

    override fun isAvailable(): Boolean {
        return services.any { it.isAvailable() }
    }

    override suspend fun shutdown() {
        services.forEach { service ->
            service.shutdown()
        }
        toolToServiceMap.clear()
    }

    /**
     * Rebuild the mapping of tool names to services
     * Called after initialization
     */
    private suspend fun rebuildToolMapping() = mutex.withLock {
        toolToServiceMap.clear()
        services.forEach { service ->
            if (service.isAvailable()) {
                val tools = service.listTools()
                tools.forEach { tool ->
                    toolToServiceMap[tool.name] = service
                }
            }
        }
        println("📋 CompositeMcpService: mapped ${toolToServiceMap.size} tools from ${services.size} services")
    }
}
