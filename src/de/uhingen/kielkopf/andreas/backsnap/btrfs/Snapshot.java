/**
 * 
 */
package de.uhingen.kielkopf.andreas.backsnap.btrfs;

import static de.uhingen.kielkopf.andreas.backsnap.Backsnap.AT_SNAPSHOTS;
import static de.uhingen.kielkopf.andreas.backsnap.Backsnap.DOT_SNAPSHOTS;
import static de.uhingen.kielkopf.andreas.beans.RecordParser.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

import de.uhingen.kielkopf.andreas.backsnap.Backsnap;
import de.uhingen.kielkopf.andreas.backsnap.Commandline;
import de.uhingen.kielkopf.andreas.backsnap.Commandline.CmdStream;
import de.uhingen.kielkopf.andreas.beans.cli.Flag;
import de.uhingen.kielkopf.andreas.beans.data.Link;

/**
 * @author Andreas Kielkopf
 * 
 *         Snapshot (readony) oder Subvolume (writable)
 */
public record Snapshot(Mount mount, Integer id, Integer gen, Integer cgen, Integer parent, Integer top_level, //
         String otime, String parent_uuid, String received_uuid, String uuid, Path btrfsPath, Link<Boolean> readonlyL) {
   static final Pattern ID=createPatternFor("ID");
   static final Pattern GEN=createPatternFor("gen");
   static final Pattern CGEN=createPatternFor("cgen");
   static final Pattern PARENT=createPatternFor("parent");
   static final Pattern TOP_LEVEL=createPatternFor("top level");
   static final Pattern OTIME=Pattern.compile("[ \\[]" + "otime" + "[ =]([^ ]+ [^ ,\\]]+)");// [ =\\[]([^ ,\\]]+)
   static final Pattern PARENT_UUID=createPatternFor("parent_uuid");
   static final Pattern RECEIVED_UUID=createPatternFor("received_uuid");
   static final Pattern UUID=createPatternFor("uuid");
   static final Pattern BTRFS_PATH=Pattern.compile("^(?:.*? )path (?:<[^>]+>)?([^ ]+).*?$");
   static final Pattern NUMERIC_DIRNAME=Pattern.compile("([0-9]+)/snapshot$");
   static final Pattern DIRNAME=Pattern.compile("([^/]+)/snapshot$");
   static final Pattern SUBVOLUME=Pattern.compile("^(@[0-9a-zA-Z.]+)/.*[0-9]+/snapshot$");
   public Snapshot(Mount mount, String from_btrfs) throws IOException {
      this(getMount(mount, getPath(BTRFS_PATH.matcher(from_btrfs))), getInt(ID.matcher(from_btrfs)),
               getInt(GEN.matcher(from_btrfs)), getInt(CGEN.matcher(from_btrfs)), getInt(PARENT.matcher(from_btrfs)),
               getInt(TOP_LEVEL.matcher(from_btrfs)), //
               getString(OTIME.matcher(from_btrfs)), getString(PARENT_UUID.matcher(from_btrfs)),
               getString(RECEIVED_UUID.matcher(from_btrfs)), getString(UUID.matcher(from_btrfs)),
               getPath(BTRFS_PATH.matcher(from_btrfs)), new Link<Boolean>("readonly"));
      if ((btrfsPath == null) || (mount == null))
         throw new FileNotFoundException("btrfs-path is missing for snapshot: " + mount + from_btrfs);
   }
   // public Snapshot(String from_btrfs) throws FileNotFoundException {
   // this(null, from_btrfs);
   // }
   static private Pattern createPatternFor(String s) {
      return Pattern.compile("^(?:.*[ \\[])?" + s + "[ =]([^ ,\\]]+)");
   }
   static public final int SORT_LEN=10; // reichen 100 Jahre ???
   /**
    * @return Key um snapshot zu sortieren sofern im Pfad ein numerischer WERT steht
    */
   public String key() {
      Matcher m=NUMERIC_DIRNAME.matcher(btrfsPath.toString());
      if (m.find())
         return dir2key(m.group(1)) + btrfsPath.toString(); // ??? numerisch sortieren ;-)
      return btrfsPath.toString();
   }
   public String keyO() {
      return new StringBuilder((mount == null) ? "null:" : mount().keyM()).append(otime()).append(idN())
               .append(btrfsPath().getFileName()).toString();
   }
   public String keyB() {
      return new StringBuilder((mount == null) ? "null:" : mount().keyM()).append(dirName())
               .append(btrfsPath().getFileName()).append(idN()).toString();
   }
   static DecimalFormat df=new DecimalFormat("0000000000");
   /**
    * @return sortable Integer
    */
   private String idN() {
      String s=Integer.toUnsignedString(id());
      String t="0".repeat(11 - s.length()) + s;
      return t;
   }
   static public final String dir2key(String dir) { // ??? numerisch sortieren ;-)
      return (dir.length() >= SORT_LEN) ? dir : ".".repeat(SORT_LEN - dir.length()).concat(dir);
   }
   public String dirName() {
      Matcher m=DIRNAME.matcher(btrfsPath.toString());
      if (m.find())
         return m.group(1);
      Path dn=btrfsPath.getParent().getFileName();
      if (dn == null)
         return null;
      return dn.toString();
   }
   /**
    * Das soll den Zeitpunkt liefern, an dem der Snapshot gemacht wurde, wenn der berechnet werden kann
    * 
    * @return Instant
    */
   public final Instant stunden() {
      try {
         String[] t=dirName().split("_");
         return Instant.parse(t[0] + "T" + t[1].replace('-', ':') + "Z");
      } catch (Exception e) {/* ignore */ }
      return null;
   }
   /**
    * @return Mount dieses Snapshots sofern im Pfad enthalten
    */
   public String subvolume() {
      Matcher m=SUBVOLUME.matcher(btrfsPath.toString());
      return (m.find()) ? m.group(1) : "";
   }
   public boolean isSubvolume() throws IOException {
      // if(isPlaisSnapshot()) return false;
      if (isReadonly())// Alles was readonly ist, ist ganz sicher kein Subvolume
         return false;
      if (isSnapper()) {
         if (isDirectMount()) // wenn er direkt gemountet ist wird er jetzt als Subvolume genutzt
            return true;
         if (!hasParent()) // Wenn keine ParentUID da ist, ist es wahrscheinlich ein Subvolume
            return true;
         return false;
      }
      if (isTimeshift()) { // Wenn es ein Timeshift-Name ist
         if (isDirectMount()) // wenn er direkt gemountet ist wird er jetzt als Subvolume genutzt
            return true;
         if (!hasParent()) // Wenn keine ParentUID da ist, ist es sicher jetzt ein Subvolume
            return true;
         return false; // ansonsten ist es ein Snapshot
      }
      if (isPlainSnapshot())
         return false;
      return true;
   }
   /**
    * The subvolume flag currently implemented is the ro property. Read-write subvolumes have that set to false,
    * snapshots as true. In addition to that, a plain snapshot will also have last change generation and creation
    * generation equal.
    * 
    * @return
    */
   boolean isPlainSnapshot() {
      return cgen == gen;
   }
   /**
    * @return Ist dieser Snapshot ReadOnly ?
    * @throws IOException
    */
   private boolean isReadonly() throws IOException {
      if (readonlyL().get() == null)
         try {
            Backsnap.BTRFS_LOCK.lock();
            String getReadonlyCmd=mount().pc()
                     .getCmd(new StringBuilder("btrfs property get ").append(getSnapshotMountPath()).append(" ro"));
            Backsnap.logln(4, getReadonlyCmd);// if (!DRYRUN.get())
            try (CmdStream getReadonlyStream=Commandline.executeCached(getReadonlyCmd, null)) { // not cached
               getReadonlyStream.backgroundErr();
               Optional<String> erg=getReadonlyStream.erg().peek(t -> Backsnap.logln(4, t))
                        .filter(t -> t.startsWith("ro=")).findAny();
               getReadonlyStream.waitFor();
               for (String line:getReadonlyStream.errList())
                  if (line.contains("No route to host") || line.contains("Connection closed")
                           || line.contains("connection unexpectedly closed")) {
                     Backsnap.disconnectCount=10;
                     break;
                  } // ende("");// R
               if (erg.isPresent()) {
                  String u=erg.get().split("=")[1];
                  boolean b=Boolean.parseBoolean(u);
                  return readonlyL().set(b);
               }
            }
         } finally {
            Backsnap.BTRFS_LOCK.unlock();
         }
      return readonlyL().get();
   }
   public boolean isBackup() {
      return received_uuid().length() > 8;
   }
   private boolean isDirectMount() {
      return false;
   }
   private boolean hasParent() {
      return parent_uuid().length() <= 8;
   }
   /**
    * @return ist das ein Timeshift-Snapshot mit standardpfad ?
    */
   private boolean isTimeshift() {
      return btrfsPath().toString().startsWith("/timeshift-btrfs/snapshots/");
   }
   private boolean isSnapper() {
      return false;
   }
   /**
    * gibt es einen mount der für diesen snapshot passt ?
    * 
    * @return mountpoint oder null
    */
   public Path getBackupMountPath() {
      if (mount == null)
         return null;
      Path rel=mount.btrfsPath().relativize(btrfsPath);
      Path abs=mount.mountPath().resolve(rel);
      return abs;
   }
   public Path getSnapshotMountPath() {
      if (mount == null)
         return null;
      if (Backsnap.TIMESHIFT.get()) {
         Optional<Mount> om=mount.pc().getTimeshiftBase();
         if (om.isPresent())
            if (om.get().devicePath().equals(mount.devicePath())) {
               Path rel=Path.of("/").relativize(btrfsPath);
               Path abs=om.get().mountPath().resolve(rel);
               return abs;
            }
      }
      Path rel=mount.btrfsPath().relativize(btrfsPath);
      Path abs=mount.mountPath().resolve(rel);
      return abs;
   }
   /**
    * Search a mountpoint that fits for this snapshot
    * 
    * @param mount0
    *           suggested mountpoint
    * @param btrfsPath1
    *           needed path
    * @return
    * @throws IOException
    */
   static private Mount getMount(Mount mount0, Path btrfsPath1) throws IOException {
      if (btrfsPath1 == null)
         return null;
      Path b2=btrfsPath1;
      Mount erg=null; // default ?
      if (!b2.toString().contains("timeshift-btrfs")) {
         for (Mount mount1:mount0.pc().getMountList(false).values())
            if (mount0.devicePath().equals(mount1.devicePath())) // only from same device
               if (b2.startsWith(mount1.btrfsPath())) // only if same path or starts with the same path
                  if ((erg == null) || (erg.btrfsPath().getNameCount() < mount1.btrfsPath().getNameCount()))
                     erg=mount1;
      } else {
         for (Mount mount1:mount0.pc().getMountList(false).values())
            if (mount0.devicePath().equals(mount1.devicePath())) // only from same device
               if (b2.getFileName().equals(mount1.btrfsPath().getFileName())) // only if ends with the same path
                  if ((erg == null) || (erg.btrfsPath().getNameCount() < mount1.btrfsPath().getNameCount()))
                     erg=mount1;
      }
      if (erg == null)
         if (b2.toString().contains("ack"))
            return null;
      return erg;
   }
   public Stream<Entry<String, String>> getInfo() {
      Map<String, String> infoMap=new LinkedHashMap<>();
      infoMap.put("btrfsPath : ", btrfsPath.toString());
      infoMap.put("otime : ", otime);
      infoMap.put("uuid : ", uuid);
      infoMap.put("parent_uuid : ", parent_uuid);
      infoMap.put("received_uuid : ", received_uuid);
      infoMap.put("gen : ", gen.toString());
      infoMap.put("id : ", id.toString());
      return infoMap.entrySet().stream();
   }
   /**
    * @param parentSnapshot2
    * @param s
    * @param b
    * @throws IOException
    */
   static public void setReadonly(Snapshot parent, Snapshot snapshot, boolean readonly) throws IOException {
      if (!snapshot.btrfsPath().toString().contains("timeshift"))
         return;
      Backsnap.BTRFS_LOCK.lock();
      try {
         if (Backsnap.bsGui != null)
            SwingUtilities.invokeLater(() -> Backsnap.bsGui.getPanelMaintenance().updateButtons());
         StringBuilder readonlySB=new StringBuilder();
         if (parent != null)
            readonlySB.append("btrfs property set ").append(parent.getSnapshotMountPath()).append(" ro ")
                     .append(readonly).append(";");
         readonlySB.append("btrfs property set ").append(snapshot.getSnapshotMountPath()).append(" ro ")
                  .append(readonly);
         String readonlyCmd=snapshot.mount().pc().getCmd(readonlySB);
         Backsnap.logln(4, readonlyCmd);// if (!DRYRUN.get())
         try (CmdStream readonlyStream=Commandline.executeCached(readonlyCmd, null)) { // not cached
            readonlyStream.backgroundErr();
            readonlyStream.erg().forEach(t -> Backsnap.logln(4, t));
            readonlyStream.waitFor();
            for (String line:readonlyStream.errList())
               if (line.contains("No route to host") || line.contains("Connection closed")
                        || line.contains("connection unexpectedly closed")) {
                  Backsnap.disconnectCount=10;
                  break;
               } // ende("");// R
         }
      } finally {
         Backsnap.BTRFS_LOCK.unlock();
         if (Backsnap.bsGui != null)
            SwingUtilities.invokeLater(() -> Backsnap.bsGui.getPanelMaintenance().updateButtons());
      }
   }
   /**
    * Setze das Readonly-Attribut dieses Snapshots
    * 
    * @param readonly
    * @throws IOException
    */
   public void setReadonly(boolean readonly) throws IOException {
      if (!isTimeshift())
         return;
      if (isReadonly() == readonly)
         return;
      Backsnap.BTRFS_LOCK.lock();
      try {
         String setReadonlyCmd=mount().pc().getCmd(new StringBuilder("btrfs property set ")
                  .append(getSnapshotMountPath()).append(" ro ").append(readonly));
         Backsnap.logln(4, setReadonlyCmd);// if (!DRYRUN.get())
         try (CmdStream setReadonlyStream=Commandline.executeCached(setReadonlyCmd, null)) { // not cached
            setReadonlyStream.backgroundErr();
            setReadonlyStream.erg().forEach(t -> Backsnap.logln(4, t));
            setReadonlyStream.waitFor();
            for (String line:setReadonlyStream.errList())
               if (line.contains("No route to host") || line.contains("Connection closed")
                        || line.contains("connection unexpectedly closed")) {
                  Backsnap.disconnectCount=10;
                  break;
               } // ende("");// R
         }
      } finally {
         Backsnap.BTRFS_LOCK.unlock();
         readonlyL().clear(); // nicht weiter im cache
      }
   }
   static public void mkain(String[] args) {
      try {
         Flag.setArgs(args, "sudo:/" + DOT_SNAPSHOTS + " /mnt/BACKUP/" + AT_SNAPSHOTS + "/manjaro");// Par. sammeln
         String backupDir=Flag.getParameterOrDefault(1, "@BackSnap");
         String source=Flag.getParameter(0);
         String externSsh=source.contains(":") ? source.substring(0, source.indexOf(":")) : "";
         String sourceDir=externSsh.isBlank() ? source : source.substring(externSsh.length() + 1);
         if (externSsh.startsWith("sudo"))
            externSsh="sudo ";
         if (externSsh.isBlank())
            externSsh="root@localhost";
         if (sourceDir.endsWith(DOT_SNAPSHOTS))
            sourceDir=sourceDir.substring(0, sourceDir.length() - DOT_SNAPSHOTS.length());
         if (sourceDir.endsWith("//"))
            sourceDir=sourceDir.substring(0, sourceDir.length() - 2);
         // SrcVolume ermitteln
         SubVolumeList subVolumes=new SubVolumeList(Pc.getPc(externSsh));
         Mount srcVolume=subVolumes.mountTree().get(sourceDir);
         if (srcVolume == null)
            throw new RuntimeException(Backsnap.LF+ "Could not find srcDir: " + sourceDir);
         if (srcVolume.btrfsMap().isEmpty())
            throw new RuntimeException(Backsnap.LF+ "Ingnoring, because there are no snapshots in: " + sourceDir);
         Backsnap.logln(1, "backup snapshots from: " + srcVolume.keyM());
         // BackupVolume ermitteln
         Mount backupVolume=subVolumes.pc().getBackupVolume();
         if (backupVolume == null)
            throw new RuntimeException(Backsnap.LF+"Could not find backupDir: " + backupDir);
         Backsnap.logln(1, "Will try to use backupDir: " + backupVolume.keyM());
         // Subdir ermitteln
         Path pathBackupDir=backupVolume.mountPath().relativize(Path.of(backupDir));
         System.out.println(pathBackupDir);
         // Verifizieren !#
         if (!subVolumes.mountTree().isEmpty())
            for (Entry<String, Mount> e:subVolumes.mountTree().entrySet()) {
               Mount subv=e.getValue();
               if (!subv.btrfsMap().isEmpty()) {// interessant sind nur die Subvolumes mit snapshots
                  String commonName=subv.getCommonName();
                  System.out.println("Found snapshots for: " + e.getKey() + " at (" + commonName + ")");
                  for (Entry<Path, Snapshot> e4:subv.btrfsMap().entrySet())
                     if (e4.getValue() instanceof Snapshot s) // @Todo obsolet ?
                        System.out.println(" -> " + e4.getKey() + " -> " + s.dirName());
               } else
                  System.out.println("NO snapshots of: " + e.getKey());
            }
         Mount backupVolumeMount=subVolumes.pc().getBackupVolume();
         System.out.println(backupVolumeMount);
         System.exit(-9);
         List<Snapshot> snapshots=new ArrayList<>();
         StringBuilder subvolumeListCmd=new StringBuilder("btrfs subvolume list -aspuqR ").append(backupDir);
         if ((externSsh instanceof String x) && (!x.isBlank()))
            if (x.startsWith("sudo "))
               subvolumeListCmd.insert(0, x);
            else
               subvolumeListCmd.insert(0, "ssh " + x + " '").append("'");
         System.out.println(subvolumeListCmd);
         try (CmdStream std=Commandline.executeCached(subvolumeListCmd, null)) {
            std.backgroundErr();
            std.erg().forEach(line -> {
               try {
                  System.out.println(line); // snapshots.add(new Snapshot(" " + line));
               } catch (Exception e) {
                  System.err.println(e);
               }
            });
            std.waitFor();
         } catch (IOException e) {
            throw e;
         } catch (Exception e) {
            e.printStackTrace();
         }
         for (Snapshot snapshot:snapshots) {
            if (snapshot.received_uuid() instanceof String ru)
               System.out.println(snapshot.dirName() + " => " + snapshot.toString());
         }
         Commandline.cleanup();
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
}
