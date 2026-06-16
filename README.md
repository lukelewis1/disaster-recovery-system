# COMP3712 Disaster Recovery System
This README is for Luke Lewis's (lewi0454) code solution for the major assignment for Advanced Algorithms and Programming COMP3712.
The JVM runtime being used for all testing and development was the OpenJDK 26.0.1.

## Solution Package Structure
The solution package is split into the responders (`algorithms/`) and the pathfinding
engine (`pathfinding/`), with shared infrastructure at the top level.
```
src/solution/
├── DisasterResponder.java       
├── PathFinder.java            
├── Graph.java                
├── GraphBuilder.java            
├── VehicleStateTracker.java    
├── RescueQueue.java            
├── DStarLite.java              
├── algorithms/
│   ├── BFS.java
│   ├── Dijkstra.java
│   ├── BidirectionalDijkstra.java
│   └── Hybrid.java
└── pathfinding/
    ├── Pathfinder.java
    ├── Bfs.java
    ├── Dijkstra.java
    ├── BidirectionalDijkstra.java
    └── DStarPathfinder.java
```

Each responder in [algorithms/](src/solution/algorithms) is the central command and control
for its strategy, delegating path computation to [PathFinder.java](src/solution/PathFinder.java),
which in turn uses the algorithms in [pathfinding/](src/solution/pathfinding).

## How to use
To use just change between implementations is the [sim.cfg](cfg/sim.cfg) file
and only the RESPONDER_CLASS parameter.

For the [Hybrid Responder](src/solution/algorithms/Hybrid.java), which uses bidirectional
Dijkstra for outbound path computation and D* Lite for return-to-base computations:
```properties
RESPONDER_CLASS=solution.algorithms.Hybrid
```
For the [BFS Responder](src/solution/algorithms/BFS.java), which uses BFS for both pathfinding
computations:
```properties
RESPONDER_CLASS=solution.algorithms.BFS
```
For the [Bidirectional Dijkstra Responder](src/solution/algorithms/BidirectionalDijkstra.java),
which uses bidirectional Dijkstra for both computations:
```properties
RESPONDER_CLASS=solution.algorithms.BidirectionalDijkstra
```
Then finally for the [Dijkstra's Algorithm Responder](src/solution/algorithms/Dijkstra.java),
which uses Dijkstra's algorithm for both computations:
```properties
RESPONDER_CLASS=solution.algorithms.Dijkstra
```
