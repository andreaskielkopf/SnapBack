/**
 * 
 */
package de.uhingen.kielkopf.andreas.backsnap.btrfs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

import de.uhingen.kielkopf.andreas.backsnap.Backsnap;
import de.uhingen.kielkopf.andreas.backsnap.Commandline;
import de.uhingen.kielkopf.andreas.backsnap.Commandline.CmdStream;

/**
 * For one Subvolume that is mounted,
 * 
 * collect all Snapshots of this Volume(device) in sorted trees
 * 
 * @author Andreas Kielkopf
 */
public record SnapTree(Mount mount, TreeMap<String, Snapshot> uuidMap, TreeMap<String, Snapshot> rUuidMap,
         TreeMap<Path, Snapshot> btrfsPathMap, TreeMap<String, Snapshot> dateMap) {
   final static ConcurrentSkipListMap<String, SnapTree> snapTreeCache=new ConcurrentSkipListMap<>();
   /**
    * create record and populate all Maps
    * 
    * @param mount
    * @throws IOException
    */
   public SnapTree(Mount mount) throws IOException {
      this(mount, new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
      populate();
   }
   private void populate() throws IOException {// otime kommt nur bei snapshots
      // mit -a bekommt man alle Snapshots für dieses Device
      StringBuilder subvolumeListCmd=new StringBuilder("btrfs subvolume list -apcguqR ").append(mount.mountPath());
      String        cmd             =mount.pc().getCmd(subvolumeListCmd);
      // if ((mount.extern() instanceof String x) && (!x.isBlank()))
      // if (x.startsWith("sudo "))
      // subvolumeListCmd.insert(0, x);
      // else
      // subvolumeListCmd.insert(0, "ssh " + x + " '").append("'");
      Backsnap.logln(3, cmd);
      try (CmdStream snapshotStream=Commandline.executeCached(cmd, mount.keyD())) {
         snapshotStream.backgroundErr();
         snapshotStream.erg().forEachOrdered(line -> {
            try {
               if (line.contains("timeshift"))
                  Backsnap.logln(8, line);
               
               Snapshot snapshot=new Snapshot(mount, line);
               btrfsPathMap.put(snapshot.btrfsPath(), snapshot);// nach pfad sortiert
               uuidMap.put(snapshot.uuid(), snapshot);
               dateMap.put(snapshot.keyO(), snapshot);
               if (snapshot.isBackup())
                  rUuidMap.put(snapshot.received_uuid(), snapshot);
               // System.out.print(".");
            } catch (FileNotFoundException e) {
               e.printStackTrace();
            }
         });
         for (String line:snapshotStream.errList())
            if (line.contains("No route to host") || line.contains("Connection closed")
                     || line.contains("connection unexpectedly closed"))
               throw new IOException(line);
      }
   }
   /**
    * Look for Snapshots of the specified mounted subvolume (But we get all snapshots of the underlying Volume, so this
    * is worth caching)
    * 
    * @param mount2
    * @param mountPoint
    * @param oextern2
    * @return a SnapTree
    * @throws IOException
    */
   public static SnapTree getSnapTree(Mount mount2) throws IOException {
      String deviceKey=mount2.keyD();
      if (!snapTreeCache.containsKey(deviceKey)) {
         snapTreeCache.put(deviceKey, new SnapTree(mount2));
         Backsnap.logln(8, "set " + deviceKey + " into treeCache");
      } else
         Backsnap.logln(8, "take " + deviceKey + " from treeCache");
      return snapTreeCache.get(deviceKey);
   }
   @Override
   public String toString() {
      StringBuilder sb=new StringBuilder("SnapTree [").append(mount.pc().extern()).append(":")
               .append(mount.devicePath()).append(" -> ").append(mount.mountPath()).append("[")//
               .append(uuidMap.size()).append(":");
      for (Snapshot s:dateMap.values())
         sb.append(s.dirName()).append(",");
      sb.setLength(sb.length() - 1);
      sb.append("]]");
      return sb.toString();
   }
}
