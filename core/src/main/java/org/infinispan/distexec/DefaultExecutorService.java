/* 
 * JBoss, Home of Professional Open Source 
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved. 
 * See the copyright.txt in the distribution for a 
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use, 
 * modify, copy, or redistribute it subject to the terms and conditions 
 * of the GNU Lesser General Public License, v. 2.1. 
 * This program is distributed in the hope that it will be useful, but WITHOUT A 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details. 
 * You should have received a copy of the GNU Lesser General Public License, 
 * v.2.1 along with this distribution; if not, write to the Free Software 
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, 
 * MA  02110-1301, USA.
 */
package org.infinispan.distexec;

import java.io.Externalizable;
import java.io.NotSerializableException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.read.DistributedExecuteCommand;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.interceptors.InterceptorChain;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.concurrent.FutureListener;
import org.infinispan.util.concurrent.NotifyingFuture;
import org.infinispan.util.concurrent.NotifyingNotifiableFuture;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Infinispan's implementation of an {@link ExecutorService} and {@link DistributedExecutorService}.
 * This ExecutorService provides methods to submit tasks for an execution on a cluster of Infinispan
 * nodes.
 * <p>
 * 
 * 
 * Note that due to potential task migration to another nodes every {@link Callable},
 * {@link Runnable} and/or {@link DistributedCallable} submitted must be either {@link Serializable}
 * or {@link Externalizable}. Also the value returned from a callable must be {@link Serializable}
 * or {@link Externalizable}. Unfortunately if the value returned is not serializable then a
 * {@link NotSerializableException} will be thrown.
 * 
 * @author Vladimir Blagojevic
 * @since 5.0
 * 
 */
public class DefaultExecutorService extends AbstractExecutorService implements DistributedExecutorService {

   private static final Log log = LogFactory.getLog(DefaultExecutorService.class);
   protected final AtomicBoolean isShutdown = new AtomicBoolean(false);
   protected final AdvancedCache cache;
   protected final RpcManager rpc;
   protected final InterceptorChain invoker;
   protected final CommandsFactory factory;

   /**
    * Create a new DefaultExecutorService given a master cache node. All distributed task executions
    * will be initiated from this cache node.
    * 
    * @param masterCacheNode
    *           cache node initiating map reduce task
    */
   public DefaultExecutorService(Cache masterCacheNode) {
      super();
      if (masterCacheNode == null)
         throw new NullPointerException("Can not use " + masterCacheNode
                  + " cache for DefaultExecutorService");
      
      ensureProperCacheState(masterCacheNode.getAdvancedCache());
      this.cache = masterCacheNode.getAdvancedCache();
      this.rpc = cache.getRpcManager();
      this.invoker = cache.getComponentRegistry().getComponent(InterceptorChain.class);
      this.factory = cache.getComponentRegistry().getComponent(CommandsFactory.class);
   }

   @Override
   public <T> NotifyingFuture<T> submit(Runnable task, T result) {      
      return (NotifyingFuture<T>) super.submit(task, result);
   }

   @Override
   public <T> NotifyingFuture<T> submit(Callable<T> task) {
      return (NotifyingFuture<T>) super.submit(task);
   }

   @Override
   public void shutdown() {
      realShutdown(false);
   }

   @SuppressWarnings("unchecked")
   private List<Runnable> realShutdown(boolean interrupt) {
      isShutdown.set(true);
      // TODO cancel all tasks
      return Collections.emptyList();
   }

   @Override
   public List<Runnable> shutdownNow() {
      return realShutdown(true);
   }

   @Override
   public boolean isShutdown() {
      return isShutdown.get();
   }

   @Override
   public boolean isTerminated() {
      if (isShutdown.get()) {
         // TODO account for all tasks
         return true;
      }
      return false;
   }

   @Override
   public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      long nanoTimeWait = unit.toNanos(timeout);
      // TODO wait for all tasks to finish
      return true;
   }

   public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException,
            ExecutionException {
      try {
         return doInvokeAny(tasks, false, 0);
      } catch (TimeoutException cannotHappen) {
         assert false;
         return null;
      }
   }

   public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
      return doInvokeAny(tasks, true, unit.toNanos(timeout));
   }

   /**
    * the main mechanics of invokeAny. This was essentially copied from
    * {@link AbstractExecutorService} doInvokeAny except that we replaced the
    * {@link ExecutorCompletionService} with our {@link DistributedExecutionCompletionService}.
    */
   private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks, boolean timed, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
      if (tasks == null)
         throw new NullPointerException();
      int ntasks = tasks.size();
      if (ntasks == 0)
         throw new IllegalArgumentException();
      List<Future<T>> futures = new ArrayList<Future<T>>(ntasks);
      CompletionService<T> ecs = new DistributedExecutionCompletionService<T>(this);

      // For efficiency, especially in executors with limited
      // parallelism, check to see if previously submitted tasks are
      // done before submitting more of them. This interleaving
      // plus the exception mechanics account for messiness of main
      // loop.

      try {
         // Record exceptions so that if we fail to obtain any
         // result, we can throw the last exception we got.
         ExecutionException ee = null;
         long lastTime = (timed) ? System.nanoTime() : 0;
         Iterator<? extends Callable<T>> it = tasks.iterator();

         // Start one task for sure; the rest incrementally
         futures.add(ecs.submit(it.next()));
         --ntasks;
         int active = 1;

         for (;;) {
            Future<T> f = ecs.poll();
            if (f == null) {
               if (ntasks > 0) {
                  --ntasks;
                  futures.add(ecs.submit(it.next()));
                  ++active;
               } else if (active == 0)
                  break;
               else if (timed) {
                  f = ecs.poll(nanos, TimeUnit.NANOSECONDS);
                  if (f == null)
                     throw new TimeoutException();
                  long now = System.nanoTime();
                  nanos -= now - lastTime;
                  lastTime = now;
               } else
                  f = ecs.take();
            }
            if (f != null) {
               --active;
               try {
                  return f.get();
               } catch (InterruptedException ie) {
                  throw ie;
               } catch (ExecutionException eex) {
                  ee = eex;
               } catch (RuntimeException rex) {
                  ee = new ExecutionException(rex);
               }
            }
         }

         if (ee == null)
            ee = new ExecutionException() {
               private static final long serialVersionUID = 200818694545553992L;
            };
         throw ee;

      } finally {
         for (Future<T> f : futures)
            f.cancel(true);
      }
   }

   @Override
   public void execute(Runnable command) {
      if (!isShutdown.get()) {
         DistributedRunnableFuture<Object> cmd = null;
         if (command instanceof DistributedRunnableFuture<?>) {
            cmd = (DistributedRunnableFuture<Object>) command;
         } else if (command instanceof Serializable) {
            cmd = (DistributedRunnableFuture<Object>) newTaskFor(command, null);
         } else {
            throw new IllegalArgumentException("Runnable command is not Serializable  " + command);
         }         
         sendForRemoteExecution(randomClusterMemberOtherThanSelf(), cmd);
      } else {
         throw new RejectedExecutionException();
      }
   }

   @Override
   protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
      if (runnable == null) throw new NullPointerException();
      
      DistributedExecuteCommand<T> executeCommand = factory.buildDistributedExecuteCommand(
               new RunnableAdapter<T>(runnable, value), rpc.getAddress(), null);
      DistributedRunnableFuture<T> future = new DistributedRunnableFuture<T>(executeCommand);
      return future;
   }

   @Override
   protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
      if (callable == null) throw new NullPointerException();
      
      DistributedExecuteCommand<T> executeCommand = factory.buildDistributedExecuteCommand(
               callable, rpc.getAddress(), null);
      DistributedRunnableFuture<T> future = new DistributedRunnableFuture<T>(executeCommand);
      return future;
   }

   @Override
   public <T, K> Future<T> submit(Callable<T> task, K... input) {
      if (task == null) throw new NullPointerException();      
      
      if(inputKeysSpecified(input)){
         Map<Address, List<K>> nodesKeysMap = mapKeysToNodes(input);
         Address me = rpc.getAddress();
         DistributedExecuteCommand<T> c = factory.buildDistributedExecuteCommand(task, me, Arrays.asList(input));
         DistributedRunnableFuture<T> f = new DistributedRunnableFuture<T>(c);
         ArrayList<Address> nodes = new ArrayList<Address>(nodesKeysMap.keySet());
         boolean invokeOnSelf = (nodes.size() == 1 && nodes.get(0).equals(me));
         if (invokeOnSelf) {
            invokeLocally(f);
         } else {
            sendForRemoteExecution(randomClusterMemberExcludingSelf(nodes), f);
         }
         return f;
      } else {
         return submit(task);
      }
   }

   @Override
   public <T> List<Future<T>> submitEverywhere(Callable<T> task) {
      if (task == null) throw new NullPointerException();
      List<Future<T>> futures = new ArrayList<Future<T>>();
      List<Address> members = rpc.getTransport().getMembers();
      Address me = rpc.getAddress();
      for (Address address : members) {
         DistributedExecuteCommand<T> c = factory.buildDistributedExecuteCommand(task, me, null);
         DistributedRunnableFuture<T> f = new DistributedRunnableFuture<T>(c);
         futures.add(f);
         if (address.equals(me)) {
            invokeLocally(f);
         } else {
            sendForRemoteExecution(address, f);
         }
      }    
      return futures;
   }

   @Override
   public <T, K> List<Future<T>> submitEverywhere(Callable<T> task, K... input) {
      if (task == null) throw new NullPointerException();
      if(inputKeysSpecified(input)) {
         List<Future<T>> futures = new ArrayList<Future<T>>();
         Address me = rpc.getAddress();
         Map<Address, List<K>> nodesKeysMap = mapKeysToNodes(input);
         for (Entry<Address, List<K>> e : nodesKeysMap.entrySet()) {
            Address target = e.getKey();            
            DistributedExecuteCommand<T> c = factory.buildDistributedExecuteCommand(task, me, e.getValue());
            DistributedRunnableFuture<T> f = new DistributedRunnableFuture<T>(c);
            futures.add(f);
            if (target.equals(me)) {
               invokeLocally(f);
            } else {
               sendForRemoteExecution(target, f);
            }
         }
         return futures;
      } else {
         return submitEverywhere(task);
      }
   }

   protected <T> void sendForRemoteExecution(Address address, DistributedRunnableFuture<T> f) {
      log.debug("Sending %s to remote execution at node %s", f, address);
      try {
         rpc.invokeRemotelyInFuture(Collections.singletonList(address), f.getCommand(), (DistributedRunnableFuture<Object>) f);
      } catch (Throwable e) {
         log.warn("Falied remote execution on node " + address, e);
      }
   }
   
   private <K> boolean inputKeysSpecified(K...input){
      return input != null && input.length > 0;
   }

   protected <T> void invokeLocally(final DistributedRunnableFuture<T> future) {
      log.debug("Sending %s to self", future);
      try {
         Callable<Object> call = new Callable<Object>() {
            
            @Override
            public Object call() throws Exception {
               Map<Address,Response> results = new HashMap<Address, Response>();   
               Object result = null;
               future.getCommand().init(cache);
               try {
                  result = future.getCommand().perform(null);
                  results.put(rpc.getAddress(), new SuccessfulResponse(result));
               } catch (Throwable e) {
                  result = e;
               } finally {
                  future.notifyDone();
               }
               return results;
            }
         };
         final FutureTask<Object> task = new FutureTask<Object>(call);
         future.setNetworkFuture((Future<T>) task);         
         task.run();
      } catch (Throwable e1) {
         log.warn("Falied local execution ", e1);
      }
   }

   protected <K> Map<Address, List<K>> mapKeysToNodes(K... input) {
      DistributionManager dm = cache.getDistributionManager();
      Map<Address, List<K>> addressToKey = new HashMap<Address, List<K>>();
      for (K key : input) {
         List<Address> nodesForKey = dm.locate(key);
         Address ownerOfKey = nodesForKey.get(0);
         List<K> keysAtNode = addressToKey.get(ownerOfKey);
         if (keysAtNode == null) {
            keysAtNode = new ArrayList<K>();
            addressToKey.put(ownerOfKey, keysAtNode);
         }
         keysAtNode.add(key);
      }
      return addressToKey;
   }

   protected List<Address> randomClusterMembers(int numNeeded) {
      List<Address> members = new ArrayList<Address>(rpc.getTransport().getMembers());
      return randomClusterMembers(members, numNeeded);
   }
   
   protected Address randomClusterMemberExcludingSelf(List<Address> members) {
      List<Address> list = randomClusterMembers(members,1);
      return list.get(0);
   }
   
   protected Address randomClusterMemberOtherThanSelf() {
     List<Address> l = randomClusterMembers(1);
     return l.get(0);
   }

   protected List<Address> randomClusterMembers(List<Address> members, int numNeeded) {
      List<Address> chosen = new ArrayList<Address>();
      members.remove(rpc.getAddress());
      if (members.size() < numNeeded) {
         log.warn("Can not select %s random members for %s", numNeeded, members);
         numNeeded = members.size();
      }
      Random r = new Random();
      while (members != null && !members.isEmpty() && numNeeded >= chosen.size()) {
         int count = members.size();
         Address address = members.remove(r.nextInt(count));
         chosen.add(address);
      }
      return chosen;
   }
   
   private void ensureProperCacheState(AdvancedCache cache) throws NullPointerException,
            IllegalStateException {
      
      if (cache.getRpcManager() == null)
         throw new IllegalStateException("Can not use non-clustered cache for DefaultExecutorService");

      if (cache.getStatus() != ComponentStatus.RUNNING)
         throw new IllegalStateException("Invalid cache state " + cache.getStatus());
   }
   
   /**
    * DistributedRunnableFuture is essentially a Future wrap around DistributedExecuteCommand. 
    * 
    * 
    * @author Mircea Markus
    * @author Vladimir Blagojevic
    */
   private static class DistributedRunnableFuture<V> implements RunnableFuture<V>, NotifyingNotifiableFuture<V> {

      protected final DistributedExecuteCommand<V> distCommand;
      protected volatile Future<V> f;

      /**
       * Creates a <tt>DistributedRunnableFuture</tt> that will upon running, execute the given
       * <tt>Runnable</tt>, and arrange that <tt>get</tt> will return the given result on successful
       * completion.
       * 
       * 
       * @param runnable
       *           the runnable task
       * @param result
       *           the result to return on successful completion.
       *
       */
      public DistributedRunnableFuture(DistributedExecuteCommand<V> command) {
         this.distCommand = command;
      }

      public DistributedExecuteCommand<V> getCommand() {
         return distCommand;
      }

      public boolean isCancelled() {
         return f.isCancelled();
      }

      public boolean isDone() {
         return f.isDone();
      }

      public boolean cancel(boolean mayInterruptIfRunning) {
         return f.cancel(mayInterruptIfRunning);
      }

      /**
       * 
       */
      public V get() throws InterruptedException, ExecutionException {
         Object response = f.get();
         return retrieveResult(response);      
      }

      /**
       * 
       */
      public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException,
               TimeoutException {
         Object response = f.get(timeout, unit);
         return retrieveResult(response);      
      }

      @Override
      public void run() {
      }

      @Override
      public void notifyDone() {
      }

      @Override
      public NotifyingFuture<V> attachListener(FutureListener<V> listener) {
         return this;
      }

      @Override
      public void setNetworkFuture(Future<V> future) {
         this.f = future;
      }
      
      private V retrieveResult(Object response) throws InterruptedException, ExecutionException {
         if (response instanceof Exception) {
            throw new ExecutionException((Exception) response);
         }
         
         Map<Address, Response> mapResult = (Map<Address, Response>) response;
         for (Entry<Address, Response> e : mapResult.entrySet()) {
            if (e.getValue() instanceof SuccessfulResponse) {
               return (V) ((SuccessfulResponse) e.getValue()).getResponseValue();
            }
         }
         throw new ExecutionException(new IllegalStateException("Invalid response " + response));
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (!(o instanceof DistributedRunnableFuture)) {
            return false;
         }

         DistributedRunnableFuture<?> that = (DistributedRunnableFuture<?>) o;
         return that.getCommand().equals(getCommand());
      }

      @Override
      public int hashCode() {
         return getCommand().hashCode();
      }
   }
   
   private static final class RunnableAdapter<T> implements Callable<T>, Serializable {

      /** The serialVersionUID */
      private static final long serialVersionUID = 6629286923873531028L;

      protected Runnable task;
      protected T result;

      protected RunnableAdapter() {
      }

      protected RunnableAdapter(Runnable task, T result) {
         this.task = task;
         this.result = result;
      }

      public T call() {
         task.run();
         return result;
      }
   }
}
