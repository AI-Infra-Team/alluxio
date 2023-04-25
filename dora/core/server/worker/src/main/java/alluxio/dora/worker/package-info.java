/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

/**
 * Worker process and utils for working with the worker remotely.
 *
 * Main entry point for the worker is {@link alluxio.dora.worker.AlluxioWorker#main}
 * which gets started by the alluxio start scripts. The {@link alluxio.dora.worker.WorkerProcess}
 * spins up the different RPC services (gRPC, data) which are mostly wrappers around
 * {@link alluxio.worker.block.BlockWorker}.
 *
 * <h1>Services</h1>
 *
 * <h2>DataServer</h2>
 *
 * This service is the main interaction between users and worker for reading and writing blocks.
 * The {@link alluxio.dora.worker.DataServer} interface defines how to start/stop, and get port
 * details; to start, object init is used. The implementation of this interface is in
 * {@link alluxio.dora.worker.grpc.GrpcDataServer}. It creates an {@link alluxio.dora.worker.DataServer}
 * instance based on gRPC which is a high performance universal remote procedure call framework.
 *
 * Data server handles the following types of block requests:
 * <ul>
 *   <li>Read blocks in worker storage from network</li>
 *   <li>Read blocks in worker storage from local file system:
 *     short-circuit read for local client</li>
 *   <li>Read blocks in UFS</li>
 *   <li>Write blocks in worker storage from network</li>
 *   <li>Write blocks in worker storage from local file system:
 *     short-circuit write for local client</li>
 *   <li>Write blocks in UFS</li>
 * </ul>
 *
 * The current protocol is described in {@link alluxio.proto.dataserver.Protocol}.
 * All the requests are generated by Protobuf to keep the protocol extensible but also
 * backward-compatible.
 *
 */
package alluxio.dora.worker;
