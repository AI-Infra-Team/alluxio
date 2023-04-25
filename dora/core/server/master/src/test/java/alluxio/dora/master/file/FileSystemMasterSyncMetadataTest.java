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

package alluxio.dora.master.file;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import alluxio.AlluxioURI;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.AccessControlException;
import alluxio.exception.FileAlreadyExistsException;
import alluxio.exception.FileDoesNotExistException;
import alluxio.exception.InvalidPathException;
import alluxio.file.options.DescendantType;
import alluxio.grpc.DeletePOptions;
import alluxio.grpc.FileSystemMasterCommonPOptions;
import alluxio.grpc.GetStatusPOptions;
import alluxio.grpc.ListStatusPOptions;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.ManuallyScheduleHeartbeat;
import alluxio.dora.master.CoreMasterContext;
import alluxio.master.MasterFactory;
import alluxio.master.MasterRegistry;
import alluxio.dora.master.MasterTestUtils;
import alluxio.dora.master.block.BlockMaster;
import alluxio.dora.master.block.BlockMasterFactory;
import alluxio.dora.master.file.contexts.CreateDirectoryContext;
import alluxio.dora.master.file.contexts.DeleteContext;
import alluxio.dora.master.file.contexts.GetStatusContext;
import alluxio.dora.master.file.contexts.ListStatusContext;
import alluxio.dora.master.file.contexts.MountContext;
import alluxio.dora.master.file.meta.Inode;
import alluxio.dora.master.file.meta.LockedInodePath;
import alluxio.master.journal.JournalSystem;
import alluxio.dora.master.journal.JournalTestUtils;
import alluxio.master.journal.JournalType;
import alluxio.dora.master.metrics.MetricsMasterFactory;
import alluxio.security.authentication.AuthenticatedClientUser;
import alluxio.security.user.UserState;
import alluxio.underfs.Fingerprint;
import alluxio.underfs.UfsDirectoryStatus;
import alluxio.underfs.UfsFileStatus;
import alluxio.underfs.UfsStatus;
import alluxio.underfs.UnderFileSystem;
import alluxio.util.IdUtils;
import alluxio.util.ModeUtils;
import alluxio.util.ThreadFactoryUtils;
import alluxio.util.executor.ExecutorServiceFactories;
import alluxio.util.executor.ExecutorServiceFactory;
import alluxio.util.io.PathUtils;
import alluxio.wire.FileInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * Unit tests for {@link FileSystemMaster}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({UnderFileSystem.Factory.class})
public final class FileSystemMasterSyncMetadataTest {
  private File mJournalFolder;
  private MasterRegistry mRegistry;
  private FileSystemMaster mFileSystemMaster;
  private UnderFileSystem mUfs;
  private ExecutorService mExecutorService;

  @Rule
  public ManuallyScheduleHeartbeat mManualScheduler =
      new ManuallyScheduleHeartbeat(HeartbeatContext.MASTER_PERSISTENCE_CHECKER,
          HeartbeatContext.MASTER_PERSISTENCE_SCHEDULER);

  @Before
  public void before() throws Exception {
    UserState s = UserState.Factory.create(Configuration.global());
    AuthenticatedClientUser.set(s.getUser().getName());
    TemporaryFolder tmpFolder = new TemporaryFolder();
    tmpFolder.create();
    File ufsRoot = tmpFolder.newFolder();
    Configuration.set(PropertyKey.MASTER_JOURNAL_TYPE, JournalType.UFS);
    Configuration.set(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS, ufsRoot.getAbsolutePath());
    Configuration.set(PropertyKey.MASTER_PERSISTENCE_INITIAL_INTERVAL_MS, 0);
    Configuration.set(PropertyKey.MASTER_PERSISTENCE_MAX_INTERVAL_MS, 1000);
    Configuration.set(PropertyKey.MASTER_PERSISTENCE_MAX_TOTAL_WAIT_TIME_MS, 1000);
    mJournalFolder = tmpFolder.newFolder();
    startServices();
  }

  /**
   * Resets global state after each test run.
   */
  @After
  public void after() throws Exception {
    stopServices();
  }

  @Test
  public void setAttributeOwnerGroupOnMetadataUpdate() throws Exception {
    AlluxioURI ufsMount = setupMockUfsS3Mount();
    String fname = "file";
    AlluxioURI uri = new AlluxioURI("/mnt/local/" + fname);
    short mode = ModeUtils.getUMask("0700").toShort();

    // Mock dir1 ufs path
    AlluxioURI filePath = ufsMount.join("file");
    UfsFileStatus fileStatus = new UfsFileStatus(
        "file", "", 0L, System.currentTimeMillis(),
        "owner1", "owner1", (short) 777, null, 100L);
    Mockito.when(mUfs.getParsedFingerprint(filePath.toString()))
        .thenReturn(Fingerprint.create("s3", fileStatus));
    Mockito.when(mUfs.exists(filePath.toString())).thenReturn(true);
    Mockito.when(mUfs.isDirectory(filePath.toString())).thenReturn(false);
    Mockito.when(mUfs.isFile(filePath.toString())).thenReturn(true);
    Mockito.when(mUfs.getStatus(filePath.toString())).thenReturn(fileStatus);

    List<FileInfo> f1 = mFileSystemMaster.listStatus(uri, ListStatusContext.mergeFrom(
        ListStatusPOptions.newBuilder().setCommonOptions(
            FileSystemMasterCommonPOptions.newBuilder().setSyncIntervalMs(0).build())));
    UfsFileStatus updatedStatus = new UfsFileStatus(
        "file", "", 0, System.currentTimeMillis(),
        "owner2", "owner2", (short) 777, null, 100);
    Mockito.when(mUfs.getStatus(filePath.toString())).thenReturn(updatedStatus);
    Mockito.when(mUfs.getParsedFingerprint(filePath.toString()))
        .thenReturn(Fingerprint.create("s3", updatedStatus));

    FileInfo res = mFileSystemMaster.getFileInfo(uri,
        GetStatusContext.mergeFrom(GetStatusPOptions.newBuilder().setCommonOptions(
            FileSystemMasterCommonPOptions.newBuilder().setSyncIntervalMs(0).build())));
    assertEquals("owner2", res.getOwner());
    assertEquals("owner2", res.getGroup());
  }

  @Test
  public void listStatusWithSyncMetadataAndEmptyS3Owner() throws Exception {

    AlluxioURI ufsMount = setupMockUfsS3Mount();
    short mode = ModeUtils.getUMask("0700").toShort();

    // Mock dir1 ufs path
    AlluxioURI dir1Path = ufsMount.join("dir1");
    UfsDirectoryStatus dir1Status = new UfsDirectoryStatus(dir1Path.getPath(), "", "", mode);
    Mockito.when(mUfs.getParsedFingerprint(dir1Path.toString()))
        .thenReturn(Fingerprint.create("s3", dir1Status));
    Mockito.when(mUfs.exists(dir1Path.toString())).thenReturn(true);
    Mockito.when(mUfs.isDirectory(dir1Path.toString())).thenReturn(true);
    Mockito.when(mUfs.isFile(dir1Path.toString())).thenReturn(false);
    Mockito.when(mUfs.getStatus(dir1Path.toString())).thenReturn(dir1Status);
    Mockito.when(mUfs.getDirectoryStatus(dir1Path.toString())).thenReturn(dir1Status);

    // Mock nested ufs path
    AlluxioURI nestedFilePath = ufsMount.join("dir1").join("file1");
    UfsFileStatus nestedFileStatus = new UfsFileStatus(nestedFilePath.getPath(), "dummy", 0,
        null, "", "", mode, 1024);
    Mockito.when(mUfs.getParsedFingerprint(nestedFilePath.toString()))
        .thenReturn(Fingerprint.create("s3", nestedFileStatus));
    Mockito.when(mUfs.getStatus(nestedFilePath.toString())).thenReturn(nestedFileStatus);
    Mockito.when(mUfs.isDirectory(nestedFilePath.toString())).thenReturn(false);
    Mockito.when(mUfs.isFile(nestedFilePath.toString())).thenReturn(true);
    Mockito.when(mUfs.getFileStatus(nestedFilePath.toString())).thenReturn(nestedFileStatus);
    Mockito.when(mUfs.exists(nestedFilePath.toString())).thenReturn(true);

    // Create directory in Alluxio only
    AlluxioURI dir1 = new AlluxioURI("/mnt/local/dir1");
    mFileSystemMaster.createDirectory(dir1, CreateDirectoryContext.defaults());

    // Mock creating the same directory and nested file in UFS out of band
    Mockito.when(mUfs.listStatus(eq(dir1Path.toString())))
        .thenReturn(new UfsStatus[]{new UfsFileStatus("file1", "dummy", 0,
        null, "", "", mode, 1024)});

    // List with sync.interval=0
    List<FileInfo> fileInfoList =
        mFileSystemMaster.listStatus(dir1, ListStatusContext.mergeFrom(
            ListStatusPOptions.newBuilder().setCommonOptions(
                FileSystemMasterCommonPOptions.newBuilder().setSyncIntervalMs(0).build())));
    assertEquals(1, fileInfoList.size());

    // Verify owner/group is not empty
    FileInfo mountLocalInfo =
        mFileSystemMaster.getFileInfo(new AlluxioURI("/mnt/local"), GetStatusContext.defaults());
    assertEquals(mountLocalInfo.getOwner(),
        mFileSystemMaster.getFileInfo(dir1, GetStatusContext.defaults()).getOwner());
    assertEquals(mountLocalInfo.getGroup(),
        mFileSystemMaster.getFileInfo(dir1, GetStatusContext.defaults()).getGroup());
    AlluxioURI file1 = new AlluxioURI("/mnt/local/dir1/file1");
    assertEquals(mountLocalInfo.getOwner(),
        mFileSystemMaster.getFileInfo(file1, GetStatusContext.defaults()).getOwner());
    assertEquals(mountLocalInfo.getGroup(),
        mFileSystemMaster.getFileInfo(file1, GetStatusContext.defaults()).getGroup());
  }

  private AlluxioURI setupMockUfsS3Mount()
      throws IOException, FileDoesNotExistException, FileAlreadyExistsException,
      AccessControlException, InvalidPathException {
    mFileSystemMaster.createDirectory(new AlluxioURI("/mnt/"), CreateDirectoryContext.defaults());
    // Mock ufs mount
    AlluxioURI ufsMount = new AlluxioURI("s3a://bucket/");
    Mockito.when(mUfs.getUnderFSType()).thenReturn("s3");
    Mockito.when(mUfs.isObjectStorage()).thenReturn(true);
    Mockito.when(mUfs.isDirectory(ufsMount.toString())).thenReturn(true);
    short mode = ModeUtils.getUMask("0700").toShort();
    Mockito.when(mUfs.getExistingDirectoryStatus(ufsMount.toString()))
        .thenReturn(new UfsDirectoryStatus(ufsMount.toString(), "", "", mode));
    Mockito.when(mUfs.resolveUri(Mockito.eq(ufsMount), anyString()))
        .thenAnswer(invocation -> new AlluxioURI(ufsMount,
            PathUtils.concatPath(ufsMount.getPath(),
                invocation.getArgument(1, String.class)), false));

    // Mount
    AlluxioURI mountLocal = new AlluxioURI("/mnt/local");
    mFileSystemMaster.mount(mountLocal, ufsMount, MountContext.defaults());

    return ufsMount;
  }

  @Test
  public void deleteAlluxioOnlyNoSync() throws Exception {
    // Prepare files
    mFileSystemMaster.createDirectory(new AlluxioURI("/a/"), CreateDirectoryContext.defaults());
    mFileSystemMaster.createDirectory(new AlluxioURI("/a/b/"), CreateDirectoryContext.defaults());
    mFileSystemMaster.createDirectory(new AlluxioURI("/b/"), CreateDirectoryContext.defaults());
    // If the sync operation happens, the flag will be marked
    SyncAwareFileSystemMaster delegateMaster = (SyncAwareFileSystemMaster) mFileSystemMaster;
    delegateMaster.setSynced(false);

    delegateMaster.delete(new AlluxioURI("/a/"),
        DeleteContext.mergeFrom(DeletePOptions.newBuilder()
            .setRecursive(true).setAlluxioOnly(true)));
    // The files have been deleted
    assertEquals(IdUtils.INVALID_FILE_ID, mFileSystemMaster.getFileId(new AlluxioURI("/a/")));
    assertEquals(IdUtils.INVALID_FILE_ID, mFileSystemMaster.getFileId(new AlluxioURI("/a/b/")));
    // Irrelevant files are not affected
    assertNotEquals(IdUtils.INVALID_FILE_ID, mFileSystemMaster.getFileId(new AlluxioURI("/b/")));
    // Sync has not happened
    assertFalse(delegateMaster.mSynced.get());
  }

  private static class SyncAwareFileSystemMaster extends DefaultFileSystemMaster {
    AtomicBoolean mSynced = new AtomicBoolean(false);

    public SyncAwareFileSystemMaster(BlockMaster blockMaster, CoreMasterContext masterContext,
                                     ExecutorServiceFactory executorServiceFactory) {
      super(blockMaster, masterContext, executorServiceFactory, Clock.systemUTC());
    }

    @Override
    InodeSyncStream.SyncStatus syncMetadata(RpcContext rpcContext, AlluxioURI path,
                                            FileSystemMasterCommonPOptions options, DescendantType syncDescendantType,
                                            @Nullable FileSystemMasterAuditContext auditContext,
                                            @Nullable Function<LockedInodePath, Inode> auditContextSrcInodeFunc)
        throws AccessControlException, InvalidPathException {
      mSynced.set(true);
      return super.syncMetadata(rpcContext, path, options, syncDescendantType, auditContext,
              auditContextSrcInodeFunc);
    }

    void setSynced(boolean synced) {
      mSynced.set(synced);
    }
  }

  private class SyncAwareFileSystemMasterFactory implements MasterFactory<CoreMasterContext> {
    @Override
    public boolean isEnabled() {
      return true;
    }

    @Override
    public String getName() {
      return "SyncAwareFileSystemMasterFactory";
    }

    @Override
    public FileSystemMaster create(MasterRegistry registry, CoreMasterContext context) {
      BlockMaster blockMaster = registry.get(BlockMaster.class);
      FileSystemMaster fileSystemMaster = new SyncAwareFileSystemMaster(blockMaster, context,
          ExecutorServiceFactories.constantExecutorServiceFactory(mExecutorService));
      registry.add(FileSystemMaster.class, fileSystemMaster);
      return fileSystemMaster;
    }
  }

  private void startServices() throws Exception {
    mExecutorService = Executors
        .newFixedThreadPool(4, ThreadFactoryUtils.build("DefaultFileSystemMasterTest-%d", true));
    mRegistry = new MasterRegistry();
    JournalSystem journalSystem =
        JournalTestUtils.createJournalSystem(mJournalFolder.getAbsolutePath());
    CoreMasterContext context = MasterTestUtils.testMasterContext(journalSystem);
    new MetricsMasterFactory().create(mRegistry, context);
    new BlockMasterFactory().create(mRegistry, context);
    mFileSystemMaster = new SyncAwareFileSystemMasterFactory().create(mRegistry, context);
    journalSystem.start();
    journalSystem.gainPrimacy();
    mRegistry.start(true);

    mUfs = Mockito.mock(UnderFileSystem.class);
    PowerMockito.mockStatic(UnderFileSystem.Factory.class);
    Mockito.when(UnderFileSystem.Factory.createWithRecorder(anyString(), any(), any()))
        .thenReturn(mUfs);
  }

  private void stopServices() throws Exception {
    mRegistry.stop();
  }
}
