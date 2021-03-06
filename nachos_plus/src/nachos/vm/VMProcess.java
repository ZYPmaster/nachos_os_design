package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
    /**
     * Allocate a new process.
     */
    public VMProcess() {
        super();
        allocatedPages = new LinkedList<Integer>();
        lazyLoadPages = new HashMap<Integer, CoffSectionAddress>();
        tlbBackUp = new TranslationEntry[Machine.processor().getTLBSize()];
        for (int i = 0; i < tlbBackUp.length; i++) {
            tlbBackUp[i] = new TranslationEntry(0, 0, false, false, false, false);
        }
    }

    protected int getFreePage() {
        //获取一个物理页
        int ppn = VMKernel.getFreePage();

        if (ppn == -1) {
            //如果没有物理页了  需要选择一页牺牲掉
            TranslationEntryWithPid victim = InvertedPageTable.getVictimPage();
            ppn = victim.getTranslationEntry().ppn;
            swapOut(victim.getPid(), victim.getTranslationEntry().vpn);
        }

        return ppn;
    }

    //重写readVirtualMemory方法
    public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        pageLock.acquire();

        int vpn = Machine.processor().pageFromAddress(vaddr);
        //从反向页表读出对应的TranslationEntry
        TranslationEntry entry = InvertedPageTable.getEntry(pid, vpn);
        if (!entry.valid) {
            //表示 此页还没有被加载到物理页中   需要重新分配物理页
            int ppn = getFreePage();
            swapIn(ppn, vpn);
        }
        entry.used = true;
        //在反向页表中 更新对应的页

        InvertedPageTable.setEntry(pid, entry);
        pageLock.release();
        return super.readVirtualMemory(vaddr, data, offset, length);
    }

    public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        pageLock.acquire();

        int vpn = Processor.pageFromAddress(vaddr);
        //由于进程只能看到TLB 所以需要将需要的页换入
        swap(vpn);
        TranslationEntry entry = InvertedPageTable.getEntry(pid, vpn);
        entry.dirty = true;
        entry.used = true;
        InvertedPageTable.setEntry(pid, entry);

        pageLock.release();

        return super.writeVirtualMemory(vaddr, data, offset, length);
    }

    protected int swap(int vpn) {
        TranslationEntry entry = InvertedPageTable.getEntry(pid, vpn);
        if (entry.valid)
            return entry.ppn;
        int ppn = getFreePage();
        swapIn(ppn, vpn);
        return ppn;
    }

    protected TranslationEntry AllocatePageTable(int vpn) {
        return InvertedPageTable.getEntry(pid, vpn);
    }


    protected void lazyLoad(int vpn, int ppn) {

        CoffSectionAddress coffSectionAddress = lazyLoadPages.remove(vpn);
        if (coffSectionAddress == null) {

            return;
        }

        CoffSection section = coff.getSection(coffSectionAddress.getSectionNumber());
        section.loadPage(coffSectionAddress.getPageOffset(), ppn);

    }

    protected void swapOut(int pid, int vpn) {
        TranslationEntry entry = InvertedPageTable.getEntry(pid, vpn);
        if (entry == null) {

            return;
        }
        if (!entry.valid) {

            return;
        }
        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
            TranslationEntry tlbEntry = Machine.processor().readTLBEntry(i);
            //遍历tbl  置换出对应的页
            if (tlbEntry.vpn == entry.vpn && tlbEntry.ppn == entry.ppn && tlbEntry.valid) {
                //将反向页表中旧的页换掉
                InvertedPageTable.updateEntry(pid, tlbEntry);
                tlbEntry.valid = false;//表示 不在内存中
                //读取反向页表中对应的新页
                entry = InvertedPageTable.getEntry(pid, vpn);
                //写入tlb
                Machine.processor().writeTLBEntry(i, tlbEntry);
                break;
            }
        }

        byte[] memory = Machine.processor().getMemory();
        int bufferOffset = Processor.makeAddress(entry.ppn, 0);
        SwapperController.getInstance(SwapFileName).writeToSwapFile(pid, vpn, memory, bufferOffset);

    }


    //取得一个物理页 然后将 发生页错误的虚拟页 装到对应的物理页中
    protected void swapIn(int ppn, int vpn) {
        TranslationEntry entry = InvertedPageTable.getEntry(pid, vpn);
        if (entry == null) {

            return;
        }
        if (entry.valid) {

            return;
        }


        boolean dirty, used;
        if (lazyLoadPages.containsKey(vpn)) {
            lazyLoad(vpn, ppn);
            dirty = true;
            used = true;
        } else {
            //如果不是首次加载此coff  则将此物理页 从交换文件 复制到主存中
            byte[] memory = Machine.processor().getMemory();

            byte[] page = SwapperController.getInstance(SwapFileName).readFromSwapFile(pid, vpn);


            //src：源数组 srcPos：源数组要复制的起始位置 dest：目标数组  destPos：目标数组复制的起始位置 length：复制的长度
            System.arraycopy(page, 0, memory, ppn * pageSize, pageSize);

            dirty = false;
            used = false;


        }
        TranslationEntry newEntry = new TranslationEntry(vpn, ppn, true, false, used, dirty);
        //更改反向页表中此页的状态
        InvertedPageTable.setEntry(pid, newEntry);
    }
    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    /**
     * 为了解决这个问题，您必须保证至少一个进程在上下文切换之前保证有2个TLB翻译以及内存中的2个对应页
     * 。这样，至少有一个进程能够在双误指令上取得进展。通过在上下文切换中保存和恢复TLB的状态，可以减少Live锁的效果，
     * 但是同样的问题可能发生在很少的物理内存页上。在这种情况下，在加载指令页和数据页之前，2个进程可能会以类似的方式被卡住。
     */
    public void saveState() {
//	super.saveState();

        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
            //保存此进程当前的TLB信息
            tlbBackUp[i] = Machine.processor().readTLBEntry(i);
            //保存此时TLB的状态到 反向页表中
            if (tlbBackUp[i].valid) {
                InvertedPageTable.updateEntry(pid, tlbBackUp[i]);
            }
        }
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    //在上下文切换后还原此进程的状态
    public void restoreState() {
//	super.restoreState();

        for (int i = 0; i < tlbBackUp.length; i++) {
            //如果此进程之前的TLB中有页在内存中  则
            if (tlbBackUp[i].valid) {
                //还原TLB信息
                Machine.processor().writeTLBEntry(i, tlbBackUp[i]);
                TranslationEntry entry = InvertedPageTable.getEntry(pid, tlbBackUp[i].vpn);
                if (entry != null && entry.valid) {
                    Machine.processor().writeTLBEntry(i, entry);
                } else {
                    Machine.processor().writeTLBEntry(i, new TranslationEntry(0, 0, false, false, false, false));
                }
            } else {

                Machine.processor().writeTLBEntry(i, new TranslationEntry(0, 0, false, false, false, false));
            }
        }
    }

    /**
     * Initializes page tables for this process so that the executable can be
     * demand-paged.
     *
     * @return <tt>true</tt> if successful.
     */
    protected boolean loadSections() {

        // load sections
        //加载coff的  section
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);

            for (int i = 0; i < section.getLength(); i++) {
                int virtualPageNum = section.getFirstVPN() + i;
                //将coffsection的加载变为懒加载
                CoffSectionAddress coffSectionAddress = new CoffSectionAddress(s, i);
                    lazyLoadPages.put(virtualPageNum, coffSectionAddress);
            }
        }

        return true;

    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
        super.unloadSections();
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param cause the user exception that occurred.
     */
    public void handleException(int cause) {
        Processor processor = Machine.processor();

        switch (cause) {
            case Processor.exceptionTLBMiss:
                //导致TLB缺失的虚拟地址是通过调用processor.readRegister(processor.regBadVAddr);获得的
                int address = processor.readRegister(Processor.regBadVAddr);
                pageLock.acquire();
                //处理TLB缺页
                boolean isSuccessful = handleTLBFault(address);
                if (!isSuccessful) {
                    UThread.finish();
                }
                pageLock.release();
                break;
            default:
                super.handleException(cause);
                break;
        }
    }

    //TLB错误  说明没有在TLB中找到对应的 虚拟页
    protected boolean handleTLBFault(int vaddr) {
        //虚拟页数
        int vpn = Processor.pageFromAddress(vaddr);
        //获取到 页错误发生的  反向页表中对应的TranslationEntry
        TranslationEntry entry = InvertedPageTable.getEntry(pid, vpn);
        if (entry == null) {
            return false;
        }
        //如果对应的页不在内存中（valid为false） 需要取一个物理页 分配物理页 （将页装入内存中） 然后装入tlb
        if (!entry.valid) {
            int ppn = getFreePage();
            swapIn(ppn, vpn);
            entry = InvertedPageTable.getEntry(pid, vpn);
        }
        //否则直接牺牲TLB中的页 然后 置换
        int victim = getTLBVictim();
        replaceTLBEntry(victim, entry);
        return true;
    }


    protected void replaceTLBEntry(int index, TranslationEntry newEntry) {
        TranslationEntry oldEntry = Machine.processor().readTLBEntry(index);
        if (oldEntry.valid) {
            InvertedPageTable.updateEntry(pid, oldEntry);
        }

        Machine.processor().writeTLBEntry(index, newEntry);
    }

    //选择一个TLB中的页牺牲掉
    protected int getTLBVictim() {
        //如果
        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
            //如果此页现在已经不在主存中  则可以将它直接置换掉
            if (!Machine.processor().readTLBEntry(i).valid) {
                return i;
            }
        }
        //否则 随机置换一个
        return Lib.random(Machine.processor().getTLBSize());

    }

    //给进程分配逻辑页  先设置页表条目  但不分配物理页
    protected boolean allocate(int vpn, int acquirePagesNum, boolean readOnly) {

        for (int i = 0; i < acquirePagesNum; ++i) {
            InvertedPageTable.insertEntry(pid, new TranslationEntry(vpn + i, 0, false, readOnly, false, false));
            SwapperController.getInstance(SwapFileName).insertUnallocatedPage(pid, vpn + i);
            allocatedPages.add(vpn + i);
        }

        numPages += acquirePagesNum;

        return true;
    }

    protected void releaseResource() {
        for (int vpn : allocatedPages) {
            pageLock.acquire();

            TranslationEntry entry = InvertedPageTable.deleteEntry(pid, vpn);
            if (entry.valid)
                VMKernel.addFreePage(entry.ppn);

            SwapperController.getInstance(SwapFileName).deletePosition(pid, vpn);

            pageLock.release();
        }
    }

    protected boolean load(String name, String[] args) {
        Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

        OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
        if (executable == null) {
            Lib.debug(dbgProcess, "\topen failed");
            return false;
        }

        try {
            coff = new Coff(executable);
        } catch (EOFException e) {
            executable.close();
            Lib.debug(dbgProcess, "\tcoff load failed");
            return false;
        }

        // make sure the sections are contiguous and start at page 0
        numPages = 0;
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);
            if (section.getFirstVPN() != numPages) {
                coff.close();
                Lib.debug(dbgProcess, "\tfragmented executable");
                return false;
            }
            //如果 所有的物理页都被分配 则 释放在主存的的那些页
            if (!allocate(numPages, section.getLength(), section.isReadOnly())) {
                releaseResource();
                return false;
            }
        }
        // make sure the argv array will fit in one page
        byte[][] argv = new byte[args.length][];
        int argsSize = 0;
        for (int i = 0; i < args.length; i++) {
            argv[i] = args[i].getBytes();
            // 4 bytes for argv[] pointer; then string plus one for null byte
            argsSize += 4 + argv[i].length + 1;
        }
        if (argsSize > pageSize) {
            coff.close();
            Lib.debug(dbgProcess, "\targuments too long");
            return false;
        }

        // program counter initially points at the program entry point
        initialPC = coff.getEntryPoint();

        // 分配堆栈页；堆栈指针最初指向它的顶部
        if (!allocate(numPages, stackPages, false)) {
            releaseResource();
            return false;
        }
        initialSP = numPages * pageSize;

        // 最后保留1页作为参数
        if (!allocate(numPages, 1, false)) {
            releaseResource();
            return false;
        }

        if (!loadSections())
            return false;

        int entryOffset = (numPages - 1) * pageSize;
        int stringOffset = entryOffset + args.length * 4;

        this.argc = args.length;
        this.argv = entryOffset;

        for (int i = 0; i < argv.length; i++) {
            byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
            Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
            entryOffset += 4;
            Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
            stringOffset += argv[i].length;
            Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[]{0}) == 1);
            stringOffset += 1;
        }

        return true;
    }

    private static final int pageSize = Processor.pageSize;


    //处理器只能看到TLB
    protected TranslationEntry[] tlbBackUp;

    protected static Lock pageLock = new Lock();
    protected LinkedList<Integer> allocatedPages;
    //实现coffsection的懒加载
    protected HashMap<Integer, CoffSectionAddress> lazyLoadPages;
    private static String SwapFileName = "Proj3SwapFile";


}
