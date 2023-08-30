package de.uhingen.kielkopf.andreas.backsnap;

import static java.lang.System.err;

import java.awt.Frame;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import de.uhingen.kielkopf.andreas.backsnap.Commandline.CmdStream;
import de.uhingen.kielkopf.andreas.backsnap.btrfs.*;
import de.uhingen.kielkopf.andreas.backsnap.gui.BacksnapGui;
import de.uhingen.kielkopf.andreas.backsnap.gui.part.SnapshotLabel.STATUS;
import de.uhingen.kielkopf.andreas.beans.cli.Flag;

/**
 * License: 'GNU General Public License v3.0'
 * 
 * © 2023
 * 
 * @author Andreas Kielkopf
 * @see https://github.com/andreaskielkopf/BackSnap
 * @see https://forum.manjaro.org/t/howto-hilfsprogramm-fur-backup-btrfs-snapshots-mit-send-recieve timeshift
 * 
 */
public class Backsnap {
   static private final String       DEFAULT_SRC      ="sudo:/";
   static private final String       DEFAULT_BACKUP   ="sudo:/mnt/BackSnap/manjaro23";
   static boolean                    usePv            =false;
   static int                        lastLine         =0;
   static String                     canNotFindParent;
   static public int                 disconnectCount  =0;
   static Future<?>                  task             =null;
   static public BacksnapGui         bsGui;
   static private int                textPos          =0;
   static final Flag                 HELP             =new Flag('h', "help");           // show usage
   static final Flag                 VERSION          =new Flag('x', "version");        // show date and version
   static final Flag                 DRYRUN           =new Flag('d', "dryrun");         // do not do anythimg ;-)
   static public final Flag          VERBOSE          =new Flag('v', "verbose");
   static public final Flag          SINGLESNAPSHOT   =new Flag('s', "singlesnapshot"); // backup exactly one snapshot
   static public final Flag          TIMESHIFT        =new Flag('t', "timeshift");
   static final Flag                 GUI              =new Flag('g', "gui");            // enable gui (only with sudo)
   static final Flag                 AUTO             =new Flag('a', "auto");           // auto-close gui when ready
   static public final Flag          COMPRESSED       =new Flag('c', "compressed");
   static public final String        SNAPSHOT         ="snapshot";
   static public final Flag          DELETEOLD        =new Flag('o', "deleteold");      // mark old snapshots for
                                                                                        // deletion
   static public final Flag          KEEP_MINIMUM     =new Flag('m', "keepminimum");    // mark all but minimum
                                                                                        // snapshots
   static public final String        BACK_SNAP_VERSION=                                 // version
            "BackSnap for Snapper and Timeshift(beta) Version 0.6.3.11 (2023/08/30)";
   static public final ReentrantLock BTRFS_LOCK       =new ReentrantLock();
   static public final String        LF               =System.lineSeparator();
   static public void main(String[] args) {
      Flag.setArgs(args, DEFAULT_SRC + " " + DEFAULT_BACKUP);
      logln(0, BACK_SNAP_VERSION);
      logln(1, "args > " + Flag.getArgs());
      if (VERSION.get())
         System.exit(0);
      if (DRYRUN.get())
         logln(0, "Doing a dry run ! ");
      TIMESHIFT.set(true);
      { // Parameter sammeln für SOURCE
         String[] source=Flag.getParameterOrDefault(0, DEFAULT_SRC).split("[:]");
         Pc srcPc=Pc.getPc(switch (source.length) {
            // case 0 -> throw new IllegalArgumentException("Ein Configfile wird noch nicht unterstützt");
            case 1 -> null; // localhost
            case 2 -> source[0]; // extern
            default -> throw new IllegalArgumentException("Mehr als ein Doppelpunkt ist nicht erlaubt");
         });
         Path srcPath=Path.of("/", source[source.length - 1].replace(Snapshot.DOT_SNAPSHOTS, ""));
         BTRFS_LOCK.lock();
         // BackupVolume ermitteln
         String[] backup=Flag.getParameterOrDefault(1, DEFAULT_BACKUP).split("[:]");
         Pc backupPc=Pc.getPc(switch (backup.length) {
            // case 0 -> throw new IllegalArgumentException("Ein Configfile wird noch nicht unterstützt");
            case 1 -> null; // localhost
            case 2 -> backup[0]; // extern
            default -> throw new IllegalArgumentException("Mehr als ein Doppelpunkt ist nicht erlaubt");
         });
         // String backupDir=backup[backup.length - 1];
         Path backupLabel=Paths.get(backup[backup.length - 1]).getFileName();
         backupPc.setBackupLabel(backupLabel);
         OneBackup.backupPc=backupPc;
         oneBackup=new OneBackup(srcPc, srcPath, backupLabel);
      }
      try {
         try { // teste ob pv da ist
            usePv=Paths.get("/bin/pv").toFile().canExecute();
         } catch (Exception e1) {/* */}
         if (TIMESHIFT.get())
            oneBackup.mountBtrfsRoot();
         // Start collecting information
         SnapConfig srcConfig=SnapConfig.getConfig(oneBackup);
         srcConfig.volumeMount().populate();
         logln(1, "Backup snapshots from " + srcConfig.volumeMount().keyM());
         OneBackup.backupPc.getMountList(false); // eventuell unnötig
         {
            Mount backupVolume=OneBackup.backupPc.getBackupVolume();
            if (backupVolume.devicePath().equals(srcConfig.volumeMount().devicePath()) && oneBackup.isSamePc())
               throw new RuntimeException(LF + "Backup is not possible onto the same device: "
                        + OneBackup.backupPc.getBackupLabel() + " <= " + oneBackup.srcPath() + LF
                        + "Please select another partition for the backup");
            logln(2, "Try to use backupDir  " + backupVolume.keyM());
            usage=new Usage(backupVolume, false);
            backupTree=SnapTree.getSnapTree(backupVolume);
         }
         if (disconnectCount > 0) {
            err.println("no SSH Connection");
            ende("X");
            System.exit(0);
         }
         bsGui=GUI.get() ? bsGui=BacksnapGui.getGui(srcConfig, backupTree, usage) : null;
         if (usage.isFull())
            throw new RuntimeException(LF + "The backup volume has less than 10GiB unallocated: " + usage.unallcoated()
                     + " of " + usage.size() + LF + "Please free some space on the backup volume");
         /// Alle Snapshots einzeln sichern
         int counter=0;
         for (Snapshot sourceSnapshot:srcConfig.volumeMount().otimeKeyMap().values()) {
            counter++;
            if (canNotFindParent != null) {
               err.println("Please remove " + Pc.MNT_BACKSNAP + "/" + OneBackup.backupPc.getBackupLabel() + "/"
                        + canNotFindParent + "/" + SNAPSHOT + " !");
               ende("X");
               System.exit(-9);
            } else
               if (disconnectCount > 3) {
                  err.println("SSH Connection lost !");
                  ende("X");
                  System.exit(-8);
               }
            try {
               if (bsGui instanceof BacksnapGui gui)
                  gui.updateProgressbar(counter, srcConfig.volumeMount().otimeKeyMap().size());
               if (!backup(sourceSnapshot, backupTree))
                  continue;
               // Anzeige im Progressbar anpassen
               if (bsGui instanceof BacksnapGui gui)
                  gui.refreshGUI(OneBackup.backupPc);
               if (SINGLESNAPSHOT.get())// nur einen Snapshot übertragen und dann abbrechen
                  break;
            } catch (NullPointerException n) {
               n.printStackTrace();
               break;
            }
         }
      } catch (IOException e) {
         if ((e.getMessage().startsWith("ssh: connect to host"))
                  || (e.getMessage().startsWith("Could not find snapshot:")))
            System.err.println(e.getMessage());
         else
            e.printStackTrace();
         ende("Xabbruch");
         System.exit(-1);
      } finally {
         BTRFS_LOCK.unlock();
         if (bsGui instanceof BacksnapGui gui)
            gui.getPanelMaintenance().updateButtons();
         if (TIMESHIFT.get())
            try {
               oneBackup.srcPc().mountBtrfsRoot(oneBackup.srcPath(), false);
            } catch (IOException e) {/* */ } // umount
      }
      ende("X");
      System.exit(-2);
   }
   static private Snapshot parentSnapshot=null;
   /**
    * Versuchen genau diesen einzelnen Snapshot zu sichern
    * 
    * @param snapConfigs
    * 
    * @param sourceKey
    * @param sMap
    * @param dMap
    * @throws IOException
    */
   static private boolean backup(Snapshot srcSnapshot, SnapTree backupMap) throws IOException {
      if (bsGui instanceof BacksnapGui gui)
         gui.setBackupInfo(srcSnapshot, parentSnapshot);
      if (srcSnapshot.isBackup()) {
         err.println("Überspringe backup vom backup: " + srcSnapshot.dirName());
         return false;
      }
      if (backupMap.rUuidMap().containsKey(srcSnapshot.uuid())) {
         if (textPos == 0) {
            lnlog(5, "Überspringe bereits vorhandene Snapshots:");
            textPos=42;
         }
         log(5, " " + srcSnapshot.dirName());
         textPos+=srcSnapshot.dirName().length() + 1;
         parentSnapshot=srcSnapshot;
         return false;
      }
      if (textPos > 0) {
         textPos=0;
         // lnlog(5, "");
      }
      // Backsnap.logln(7, srcSnapshot.getSnapshotMountPath().toString());
      logln(9, "Paths.get(backupDir=" + oneBackup.backupLabel() + " dirName=" + srcSnapshot.dirName() + ")");
      Path bDir=Paths.get(Pc.MNT_BACKSNAP, oneBackup.backupLabel().toString(), srcSnapshot.dirName());
      Path bSnapDir=backupMap.mount().btrfsPath().resolve(backupMap.mount().mountPath().relativize(bDir))
               .resolve(SNAPSHOT);
      lnlog(3, bSnapDir.toString());
      if (backupMap.btrfsPathMap().containsKey(bSnapDir)) {
         logln(5, "Der Snapshot scheint schon da zu sein ????");
         return true;
      }
      log(3, "Backup of " + srcSnapshot.dirName()
               + (parentSnapshot instanceof Snapshot ps ? " based on " + ps.dirName() : ""));
      mkDirs(bDir);
      rsyncFiles(srcSnapshot.getSnapshotMountPath(), bDir);
      if (GUI.get())
         bsGui.mark(srcSnapshot.uuid(), STATUS.INPROGRESS); // Pc backupPc=backupMap.mount().pc();
      if (sendBtrfs(srcSnapshot, bDir))
         parentSnapshot=srcSnapshot;
      return true;
   }
   static private boolean sendBtrfs(Snapshot s, Path bDir) throws IOException {
      StringBuilder btrfsSendSB=new StringBuilder("/bin/btrfs send ");
      if (bsGui instanceof BacksnapGui gui)
         gui.setBackupInfo(s, parentSnapshot);
      if (COMPRESSED.get() && !oneBackup.compressionPossible())
         COMPRESSED.set(false);
      if (COMPRESSED.get())
         btrfsSendSB.append("--proto 2 --compressed-data ");
      if (parentSnapshot instanceof Snapshot p)
         btrfsSendSB.append("-p ").append(p.getSnapshotMountPath()).append(" ");
      if (s.btrfsPath().toString().contains("timeshift-btrfs"))
         Snapshot.setReadonly(parentSnapshot, s, true);
      logln(2, "");
      btrfsSendSB.append(s.getSnapshotMountPath());
      if (!oneBackup.isSameSsh())
         if (oneBackup.isExtern())
            btrfsSendSB.insert(0, "ssh " + oneBackup.extern() + " '").append("'");
         else
            btrfsSendSB.insert(0, oneBackup.extern());
      if (usePv)
         btrfsSendSB.append("|/bin/pv -f");
      btrfsSendSB.append("|");
      if (!oneBackup.isSameSsh())
         if (OneBackup.isBackupExtern())
            btrfsSendSB.append("ssh " + OneBackup.backupPc.extern() + " '");
         else
            btrfsSendSB.append(OneBackup.backupPc.extern());
      btrfsSendSB.append("/bin/btrfs receive ").append(bDir).append(";/bin/sync");
      if (oneBackup.isSameSsh())
         if (oneBackup.isExtern())
            btrfsSendSB.insert(0, "ssh " + oneBackup.extern() + " '");
      // else
      // send_cmd.insert(0, srcSsh); // @todo für einzelnes sudo anpassen ?
      if (OneBackup.isBackupExtern())
         btrfsSendSB.append("'");
      logln(2, btrfsSendSB.toString());
      if (!DRYRUN.get()) {
         if (bsGui != null)
            bsGui.getPanelMaintenance().updateButtons();
         BTRFS_LOCK.lock();
         try (CmdStream btrfsSendStream=Commandline.executeCached(btrfsSendSB, null)) {
            task=Commandline.background.submit(() -> btrfsSendStream.err().forEach(line -> {
               if (line.contains("ERROR: cannot find parent subvolume"))
                  Backsnap.canNotFindParent=line;
               if (line.contains("No route to host") || line.contains("Connection closed")
                        || line.contains("connection unexpectedly closed"))
                  Backsnap.disconnectCount=10;
               if (line.contains("<=>")) { // from pv
                  err.print(line);
                  show(line);
                  String lf=(Backsnap.lastLine == 0) ? "\n" : "\r";
                  err.print(lf);
                  Backsnap.lastLine=line.length();
                  if (line.contains(":00 ")) {
                     err.print("\n");
                     Backsnap.disconnectCount=0;
                  }
                  if (line.contains("0,00 B/s")) {
                     err.println();
                     err.println("HipCup");
                     Backsnap.disconnectCount++;
                  }
               } else {
                  if (Backsnap.lastLine != 0) {
                     Backsnap.lastLine=0;
                     err.println();
                  }
                  err.println(line);
                  show(line);
               }
            }));
            btrfsSendStream.erg().forEach(line -> {
               if (lastLine != 0) {
                  lastLine=0;
                  logln(3, "");
               }
               logln(3, "");
            });
         } finally {
            BTRFS_LOCK.unlock();
            if (bsGui != null)
               bsGui.getPanelMaintenance().updateButtons();
            err.println();
         } // ende("S");// B
      }
      if (s.btrfsPath().toString().contains("timeshift-btrfs"))
         Snapshot.setReadonly(parentSnapshot, s, false);
      return true;
   }
   static StringBuilder     pv=new StringBuilder("- Info -");
   private static OneBackup oneBackup;
   private static Usage     usage;
   private static SnapTree  backupTree;
   /**
    * @param line
    */
   static private final void show(String line) {
      if (bsGui == null)
         return;
      line.replaceAll("[\n\r]?", " "); // if (line.equals("\n") || line.equals("\r")) return;
      if (!line.isBlank())
         bsGui.lblPvSetText(line);
   }
   static private void rsyncFiles(Path sDir, Path bDir) throws IOException {
      StringBuilder rsyncSB=new StringBuilder("/bin/rsync -vdcptgo --exclude \"@*\" --exclude \"" + SNAPSHOT + "\" ");
      if (DRYRUN.get())
         rsyncSB.append("--dry-run ");
      if (!oneBackup.isSamePc() && (oneBackup.isExtern()))
         rsyncSB.append(oneBackup.extern()).append(":");
      rsyncSB.append(sDir.getParent()).append("/ ");
      if (!oneBackup.isSamePc() && (OneBackup.isBackupExtern()))
         rsyncSB.append(OneBackup.backupPc.extern()).append(":");
      rsyncSB.append(bDir).append("/");
      if (oneBackup.isSamePc())
         if (oneBackup.isExtern())
            rsyncSB.insert(0, "ssh " + oneBackup.extern() + " '").append("'"); // gesamten Befehl senden ;-)
         else
            rsyncSB.insert(0, oneBackup.extern()); // nur sudo, kein quoting !
      String rsyncCmd=rsyncSB.toString();
      logln(4, rsyncCmd);// if (!DRYRUN.get())
      try (CmdStream rsyncStream=Commandline.executeCached(rsyncCmd, null)) { // not cached
         rsyncStream.backgroundErr();
         rsyncStream.erg().forEach(t -> logln(4, t));
         rsyncStream.waitFor();
         for (String line:rsyncStream.errList())
            if (line.contains("No route to host") || line.contains("Connection closed")
                     || line.contains("connection unexpectedly closed")) {
               Backsnap.disconnectCount=10;
               break;
            } // ende("");// R
      }
   }
   /**
    * löscht eines der Backups im Auftrag der GUI
    * 
    * @param s
    * @throws IOException
    */
   static public void removeSnapshot(Snapshot s) throws IOException {
      Path bmp=s.getBackupMountPath();
      if (!bmp.toString().startsWith(Pc.MNT_BACKSNAP) || bmp.toString().contains("../"))
         throw new SecurityException("I am not allowed to delete " + bmp.toString());
      StringBuilder removeSB=new StringBuilder("/bin/btrfs subvolume delete -Cv ").append(bmp);
      String removeCmd=s.mount().pc().getCmd(removeSB);
      if (bsGui != null)
         bsGui.setDeleteInfo(s);
      log(4, removeCmd);
      if (bsGui != null) {
         bsGui.getPanelMaintenance().updateButtons();
         String text="<html>" + s.btrfsPath().toString();
         logln(7, text);
         bsGui.mark(s.received_uuid(), STATUS.INPROGRESS);
      }
      BTRFS_LOCK.lock();
      try (CmdStream removeStream=Commandline.executeCached(removeCmd, null)) {
         removeStream.backgroundErr();
         logln(1, "");
         removeStream.erg().forEach(line -> {
            log(1, line);
            if (GUI.get())
               bsGui.lblPvSetText(line);
         });
         removeStream.waitFor();
      } finally {
         BTRFS_LOCK.unlock();
         if (bsGui != null)
            bsGui.getPanelMaintenance().updateButtons();
      }
   }
   /**
    * @param bdir
    * @param backupSsh
    * @return
    * @throws IOException
    */
   static private void mkDirs(Path bdir) throws IOException {
      if (bdir.isAbsolute()) {
         String mkdirCmd=OneBackup.backupPc.getCmd(new StringBuilder("mkdir -pv ").append(bdir));
         log(6, mkdirCmd);
         if (DRYRUN.get())
            return;
         if (!OneBackup.isBackupExtern())
            if (bdir.toFile().mkdirs())
               return; // erst mit sudo, dann noch mal mit localhost probieren
         try (CmdStream mkdirStream=Commandline.executeCached(mkdirCmd, null)) {
            mkdirStream.backgroundErr();
            mkdirStream.waitFor();
            if (mkdirStream.erg().peek(t -> logln(6, t)).anyMatch(Pattern.compile("mkdir").asPredicate()))
               return;
            if (mkdirStream.errList().isEmpty())
               return;
         }
      }
      throw new FileNotFoundException("Could not create dir: " + bdir);
   }
   /**
    * prozesse aufräumen
    * 
    * @param t
    */
   static private final void ende(String t) {
      log(4, "ende:");
      if (task != null)
         try {
            task.get(10, TimeUnit.SECONDS);
         } catch (InterruptedException | ExecutionException | TimeoutException e) {
            e.printStackTrace();
         }
      log(4, t);
      if (t.startsWith("X")) {
         log(4, " ready");
         if (GUI.get()) {
            if (bsGui != null)
               SwingUtilities.invokeLater(() -> {
                  JProgressBar sl=bsGui.getSpeedBar();
                  sl.setString("Ready to exit".toString()); // sl.repaint(100);
               });
            if (AUTO.get()) {
               if ((bsGui != null) && (bsGui.frame instanceof Frame frame)) {
                  final float FAKTOR=2f;
                  final int countdownStart=(int) (FAKTOR * ((AUTO.getParameterOrDefault(10) instanceof Integer n) ? n : 10));
                  final JProgressBar speedBar=bsGui.getSpeedBar();
                  SwingUtilities.invokeLater(() -> speedBar.setMaximum(countdownStart));
                  int countdown=countdownStart;
                  while (countdown-- > 0) {
                     final float f=countdown / FAKTOR;
                     final int p=countdownStart - countdown;
                     SwingUtilities.invokeLater(() -> {
                        speedBar.setValue(p);
                        speedBar.setString(String.format("%2.1f sec till exit", f));
//                        speedBar.repaint(50);
                     });
                     try {
                        Thread.sleep((long) (1000/FAKTOR));
                        while (bsGui.getTglPause().isSelected())
                           Thread.sleep(50);
                     } catch (InterruptedException ignore) {/* ignore */}
                  }
                  bsGui.getPrefs().
                  saveFramePos(bsGui.frame);
                  frame.setVisible(false);
                  frame.dispose();
               }
            } else
               while (bsGui != null)
                  try {
                     if (bsGui.frame == null)
                        break;
                     Thread.sleep(1000);
                  } catch (InterruptedException ignore) {/* */}
         }
         log(4, " to");
         Commandline.background.shutdown();
         log(4, " exit");
         Commandline.cleanup();
         logln(4, " java");
         if (AUTO.get())
            System.exit(0);
      }
      // logln(4, "");
   }
   static int       logPos   =0;
   final static int logMAXLEN=120;
   static public void log(int level, String text) {
      if ((Backsnap.VERBOSE.getParameterOrDefault(1) instanceof Integer v) && (v >= level)) {
         if (logPos + text.length() > logMAXLEN) {
            System.out.print(System.lineSeparator());
            logPos=0;
         }
         System.out.print(text);
         logPos+=text.length();
      }
   }
   static public void logln(int level, String text) {
      if ((Backsnap.VERBOSE.getParameterOrDefault(1) instanceof Integer v) && (v >= level)) {
         System.out.print(text + System.lineSeparator());
         logPos=0;
      }
   }
   static public void lnlog(int level, String text) {
      if ((Backsnap.VERBOSE.getParameterOrDefault(1) instanceof Integer v) && (v >= level)) {
         System.out.print(System.lineSeparator() + text);
         logPos=text.length();
      }
   }
}
