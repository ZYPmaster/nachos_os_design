package nachos.vm;


import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.machine.OpenFile;
import nachos.threads.ThreadedKernel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

/**
 *
 *
 *
 * 如果进程需要将虚拟页放到物理内存中而此时已经没有空闲的物理页，操作系统必须废弃物理空间中的另一页，为该页让出空间。
 *
 *
 *
 * 如果物理内存中需要废弃的页来自磁盘上的映像或者数据文件，而且没有被写过所以不需要存储，则该页被废弃
 * 。如果进程又需要该页，它可以从映像或数据文件中再次加载到内存中。
 *
 * 但是，如果该页已经被改变，操作系统必须保留它的内容以便以后进行访问。这种也叫做dirty page，
 * 当它从物理内存中废弃时，被存到一种叫做交换文件的特殊文件中。因为访问交换文件的速度和访问处理器以及物理内存的速度相比很慢，
 * 操作系统必须判断是将数据页写到磁盘上还是将它们保留在内存中以便下次访问。
 */
//将物理页中  不需要的置换到 磁盘上  将需要的 置换到内存中  物理内存一共16页
public class Swapper {

    private int pageSize;

    //要交换的文件
    private OpenFile swapFile;

    //内存中的文件名
    private String swapFileName;


    private HashMap<PidAndVpn,Integer> swapTable;

    private HashSet<PidAndVpn> unallocated;//未分配列表

    private LinkedList<Integer> availableLocations;//可用位置

    private static Swapper instance=null;

    protected final static char dbgVM='v';
    private Swapper(String swapFileName){
        pageSize=Machine.processor().pageSize;
        this.swapFileName=swapFileName;
        swapTable=new HashMap<PidAndVpn,Integer>();
        unallocated=new HashSet<PidAndVpn>();
        availableLocations=new LinkedList<Integer>();
        swapFile=ThreadedKernel.fileSystem.open(swapFileName, true);
        if(swapFile==null){
            Lib.debug(dbgVM, "无法打开此文件");
        }
    }

    public static Swapper getInstance(String swapFileName){
        if(instance==null){
            instance=new Swapper(swapFileName);
        }
        return instance;
    }
    //读取交换文件
    public byte[] readFromSwapFile(int pid,int vpn)
    {
        int position=findEntry(pid,vpn);
        if(position==-1){
            Lib.debug(dbgVM, "不存在此交换页");
            return new byte[pageSize];
        }
        byte[] memcopy=new byte[pageSize];
        int length=swapFile.read(position*pageSize, memcopy, 0, pageSize);
        if(length==-1){
            Lib.debug(dbgVM, "文件读取失败");
            return new byte[pageSize];
        }
        return memcopy;
    }
    //写入交换文件
    public int writeToSwapFile(int pid,int vpn,byte[] page,int offset){
        //先 为进程在swapTable中分配位置  表示已经交换
        int position=allocatePosition(pid,vpn);
        if(position==-1){
            Lib.debug(dbgVM, "分配位置错误");
            return -1;
        }
        //写入交换文件
        swapFile.write(position*pageSize, page, offset, pageSize);
        return position;
    }


    //在swapTable中为进程分配位置
    public int allocatePosition(int pid,int vpn){
        PidAndVpn pidAndVpn=new PidAndVpn(pid,vpn);
        if(unallocated.contains(pidAndVpn)){
            unallocated.remove(pidAndVpn);
            if(availableLocations.isEmpty()){
                availableLocations.add(swapTable.size());
            }
            //分配位置
            int index=availableLocations.removeFirst();
            swapTable.put(pidAndVpn, index);
            return index;
        }else{
            //位置已经分配 直接返回
            int index=-1;
            index=swapTable.get(pidAndVpn);
            if(index==-1){
                Lib.debug(dbgVM, "未分配列表与已交换列表有关此虚拟页的信息不同");
            }
            return index;
        }
    }


    //获取进程虚拟页的 位置
    private int findEntry(int pid, int vpn) {
        Integer position = swapTable.get(new PidAndVpn(pid, vpn));
        if (position == null)
            return -1;
        else
            return position.intValue();
    }
}
