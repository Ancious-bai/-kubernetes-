<template>
  <div class="app-container">
    <header class="app-header">
      <h1>YOLO 训练管理系统</h1>
    </header>

    <main class="app-main">
      <el-alert
        v-if="message"
        :title="message"
        :type="messageType"
        show-icon
        :closable="true"
        @close="message = ''"
        style="margin-bottom: 20px"
      />

      <div class="add-dataset-section">
        <el-card title="添加数据集和训练调度" shadow="hover">
          <el-form :model="form" label-width="120px" @submit.prevent="handleAddDataset">
            <div class="form-row">
              <el-form-item label="数据路径" label-width="100px" class="flex-item">
                <div class="input-container">
                  <el-input
                    v-model="form.inputDir"
                    placeholder="请输入数据路径，如：E:\Ancious\Desktop\data1"
                    class="input-path"
                    @keyup.enter="handleAddDataset"
                    size="large"
                  />
                  <el-button
                    type="info"
                    @click="browseFolder('请选择数据集文件夹', 'input')"
                    size="large"
                  >
                    浏览
                  </el-button>
                  <el-button
                    type="primary"
                    @click="handleAddDataset"
                    :loading="currentStep === 'addDataset'"
                    size="large"
                  >
                    添加数据集
                  </el-button>
                </div>
              </el-form-item>
              <el-form-item label="同时训练数" label-width="120px" class="flex-item">
                <div class="input-container">
                  <el-input-number
                    v-model="maxConcurrentTasks"
                    :min="1"
                    :max="10"
                    size="large"
                    style="width: 140px"
                  />
                  <el-button
                    type="success"
                    @click="handleMaxConcurrentTasksChange(maxConcurrentTasks)"
                    size="large"
                  >
                    提交
                  </el-button>
                  <span class="status-indicator">
                    <el-tag type="info" size="small">
                      当前最大训练数: {{ savedMaxConcurrentTasks }}
                    </el-tag>
                  </span>
                </div>
              </el-form-item>
            </div>
          </el-form>
        </el-card>
      </div>

      <el-dialog
        title="保存模型"
        v-model="saveModelDialogVisible"
        width="500px"
        :close-on-click-modal="false"
      >
        <el-form :model="saveModelForm" label-width="100px">
          <el-form-item label="数据集">
            <el-tag type="success">{{ saveModelForm.dataName }}</el-tag>
          </el-form-item>
          <el-form-item label="模型类型">
            <el-radio-group v-model="saveModelForm.modelType">
              <el-radio label="best">最佳模型 (best.pt)</el-radio>
              <el-radio label="last">最后模型 (last.pt)</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="保存路径">
            <div class="input-container">
              <el-input
                v-model="saveModelForm.savePath"
                placeholder="默认保存到项目 saved_models 目录"
                size="large"
              />
              <el-button
                type="info"
                @click="browseFolder('请选择模型保存路径', 'save')"
                size="large"
              >
                浏览
              </el-button>
              <el-button @click="saveModelForm.savePath = 'E:\\Ancious\\Desktop\\毕业设计\\Code\\saved_models'">
                默认
              </el-button>
            </div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button type="primary" @click="confirmSaveModel" :loading="saveModelLoading">
            保存模型
          </el-button>
        </template>
      </el-dialog>

      <div class="datasets-section">
        <el-card title="数据集管理" shadow="hover">
          <el-table :data="pagedDatasets" style="width: 100%" border>
            <el-table-column prop="name" label="数据集名称" width="150" />
            
            <el-table-column label="预处理" min-width="200">
              <template #default="scope">
                <div class="operation-item">
                  <el-tag :type="scope.row.preprocessed ? 'success' : 'info'" class="status-tag">
                    {{ scope.row.preprocessed ? '已完成' : '未处理' }}
                  </el-tag>
                  <el-button 
                    size="small" 
                    type="warning"
                    :disabled="scope.row.preprocessed || processingStatus[scope.row.name]"
                    @click="handlePreprocessDataset(scope.row.name)"
                  >
                    开始预处理
                  </el-button>
                </div>
              </template>
            </el-table-column>
            
            <el-table-column label="优先级（1最高）" width="150">
              <template #default="scope">
                <div class="priority-container">
                  <el-input-number
                    v-model="scope.row.priority"
                    :min="1"
                    :max="10"
                    :disabled="scope.row.internalTrainStatus === 'RUNNING'"
                    @change="handlePriorityChange(scope.row.name, $event)"
                    size="small"
                  />
                </div>
              </template>
            </el-table-column>
            
            <el-table-column label="训练进度" width="180">
              <template #default="scope">
                <div v-if="scope.row.trainProgress > 0" class="progress-container">
                  <el-progress
                    :percentage="scope.row.trainProgress"
                    :stroke-width="8"
                  />
                </div>
                <span v-else class="no-progress">-</span>
              </template>
            </el-table-column>
            
            <el-table-column label="训练" min-width="350">
              <template #default="scope">
                <div class="operation-item">
                  <el-tag :type="getStatusClass(scope.row.internalTrainStatus, scope.row.trained)" class="status-tag">
                    {{ getStatusText(scope.row.internalTrainStatus, 'train', scope.row.trained) }}
                  </el-tag>
                  <el-button 
                    size="small" 
                    type="primary"
                    :disabled="!scope.row.preprocessed || scope.row.internalTrainStatus === 'RUNNING' || scope.row.internalTrainStatus === 'QUEUED'"
                    @click="handleScheduleTrain(scope.row.name)"
                  >
                    加入队列
                  </el-button>
                  <el-button 
                    size="small" 
                    type="info"
                    :disabled="scope.row.internalTrainStatus === 'IDLE' || !scope.row.internalTrainStatus"
                    @click="handleViewTrainLogs(scope.row.name)"
                  >
                    训练日志
                  </el-button>
                  <el-button 
                    size="small" 
                    type="success"
                    :disabled="!scope.row.trained"
                    @click="showSaveModelDialog(scope.row.name)"
                  >
                    保存模型
                  </el-button>
                </div>
              </template>
            </el-table-column>
            
            <el-table-column label="测试" min-width="250">
              <template #default="scope">
                <div class="operation-item">
                  <el-tag :type="getStatusClass(scope.row.internalTestStatus, scope.row.tested)" class="status-tag">
                    {{ getStatusText(scope.row.internalTestStatus, 'test', scope.row.tested) }}
                  </el-tag>
                  <el-button 
                    size="small" 
                    type="success"
                    :disabled="!scope.row.trained || processingStatus[scope.row.name]"
                    @click="handleTestDataset(scope.row.name)"
                  >
                    开始测试
                  </el-button>
                  <el-button 
                    size="small" 
                    type="info"
                    :disabled="scope.row.internalTestStatus === 'IDLE' || !scope.row.internalTestStatus"
                    @click="handleViewTestLogs(scope.row.name)"
                  >
                    测试日志
                  </el-button>
                </div>
              </template>
            </el-table-column>
            
            <el-table-column label="操作" width="100">
              <template #default="scope">
                <el-button 
                  size="small" 
                  type="danger"
                  @click="handleDeleteDataset(scope.row.name)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          
          <div class="pagination-container">
            <el-pagination
              v-model:current-page="currentPage"
              :page-size="pageSize"
              :total="total"
              :page-sizes="[5, 10, 20, 50]"
              layout="total, sizes, prev, pager, next, jumper"
              @size-change="handleSizeChange"
              @current-change="handleCurrentChange"
            />
          </div>
        </el-card>
      </div>

      <div class="logs-section" v-if="Object.keys(preprocessLogs).length > 0">
        <el-card title="预处理日志" shadow="hover">
          <div class="log-grid">
            <div v-for="(log, dataName) in preprocessLogs" :key="dataName" class="log-item">
              <el-card shadow="hover" class="log-card">
                <template #header>
                  <div class="log-card-header">
                    <span>{{ dataName }} - 预处理</span>
                    <el-button type="danger" size="small" @click="closePreprocessLog(dataName)">关闭</el-button>
                  </div>
                </template>
                <div class="log-container" :ref="el => setLogRef(dataName, 'preprocess', el)">
                  <pre class="log-content">{{ log }}</pre>
                </div>
              </el-card>
            </div>
          </div>
        </el-card>
      </div>

      <div class="logs-section" v-if="Object.keys(trainLogs).length > 0">
        <el-card title="训练日志" shadow="hover">
          <div class="log-grid">
            <div v-for="(log, dataName) in trainLogs" :key="dataName" class="log-item">
              <el-card shadow="hover" class="log-card">
                <template #header>
                  <div class="log-card-header">
                    <span>{{ dataName }} - 训练</span>
                    <el-button type="danger" size="small" @click="closeTrainLog(dataName)">关闭</el-button>
                  </div>
                </template>
                <div class="log-container" :ref="el => setLogRef(dataName, 'train', el)">
                  <pre class="log-content">{{ log }}</pre>
                </div>
              </el-card>
            </div>
          </div>
        </el-card>
      </div>

      <div class="logs-section" v-if="Object.keys(testLogs).length > 0">
        <el-card title="测试日志" shadow="hover">
          <div class="log-grid">
            <div v-for="(log, dataName) in testLogs" :key="dataName" class="log-item">
              <el-card shadow="hover" class="log-card">
                <template #header>
                  <div class="log-card-header">
                    <span>{{ dataName }} - 测试</span>
                    <el-button type="danger" size="small" @click="closeTestLog(dataName)">关闭</el-button>
                  </div>
                </template>
                <div class="log-container" :ref="el => setLogRef(dataName, 'test', el)">
                  <pre class="log-content">{{ log }}</pre>
                </div>
              </el-card>
            </div>
          </div>
        </el-card>
      </div>

      <el-dialog 
        title="确认删除" 
        v-model="deleteConfirmVisible" 
        width="30%"
      >
        <p>确定要删除数据集 <strong>{{ datasetToDelete }}</strong> 吗？</p>
        <p style="color: #f56c6c; font-size: 13px;">此操作将删除相关的 Kubernetes Job 和 Pod。</p>
        <template #footer>
          <el-button @click="cancelDelete">取消</el-button>
          <el-button type="danger" @click="confirmDelete">确认删除</el-button>
        </template>
      </el-dialog>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, computed, watch } from 'vue'
import axios from 'axios'

const form = reactive({ inputDir: '' })

const maxConcurrentTasks = ref(2)
const savedMaxConcurrentTasks = ref(2)
const currentStep = ref('')
const datasetList = ref([])
const deleteConfirmVisible = ref(false)
const datasetToDelete = ref('')
const message = ref('')
const messageType = ref('success')
const preprocessLogs = ref({})
const trainLogs = ref({})
const testLogs = ref({})
const processingStatus = ref({})
const closedLogs = ref({})
const pollingIntervals = ref({})

const logRefs = ref({})
const setLogRef = (dataName, type, el) => {
  if (el) {
    logRefs.value[`${dataName}-${type}`] = el
  }
}

const autoScrollLog = (dataName, type) => {
  nextTick(() => {
    const el = logRefs.value[`${dataName}-${type}`]
    if (el) {
      const isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
      if (isNearBottom) {
        el.scrollTop = el.scrollHeight
      }
    }
  })
}

watch(trainLogs, () => {
  Object.keys(trainLogs.value).forEach(dataName => {
    autoScrollLog(dataName, 'train')
  })
}, { deep: true })

watch(testLogs, () => {
  Object.keys(testLogs.value).forEach(dataName => {
    autoScrollLog(dataName, 'test')
  })
}, { deep: true })

watch(preprocessLogs, () => {
  Object.keys(preprocessLogs.value).forEach(dataName => {
    autoScrollLog(dataName, 'preprocess')
  })
}, { deep: true })

const saveModelDialogVisible = ref(false)
const saveModelForm = reactive({
  dataName: '',
  modelType: 'best',
  savePath: ''
})
const saveModelLoading = ref(false)

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const pagedDatasets = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return datasetList.value.slice(start, end)
})

const handleAddDataset = async () => {
  if (!form.inputDir) {
    message.value = '请输入数据路径'
    messageType.value = 'warning'
    return
  }
  
  const inputDir = form.inputDir.trim().replace(/^["']|["']$/g, '')
  const dataName = inputDir.split('\\').pop()
  processingStatus.value[dataName] = true
  currentStep.value = 'addDataset'
  
  try {
    await axios.post('/api/datasets', { inputDir })
    await refreshDatasets()
    message.value = `数据集 ${dataName} 添加成功`
    messageType.value = 'success'
  } catch (error) {
    message.value = `添加数据集失败: ${error.response?.data?.message || error.message}`
    messageType.value = 'error'
  } finally {
    processingStatus.value[dataName] = false
    currentStep.value = ''
    form.inputDir = ''
  }
}

const browseFolder = async (title, target) => {
  try {
    const response = await axios.get('/api/dialog/folder', { params: { title } })
    if (response.data.status === 'success' && response.data.path) {
      if (target === 'input') {
        form.inputDir = response.data.path
      } else if (target === 'save') {
        saveModelForm.savePath = response.data.path
      }
    }
  } catch (error) {
    message.value = '打开文件夹选择器失败'
    messageType.value = 'error'
  }
}

const handlePreprocessDataset = async (dataName) => {
  processingStatus.value[dataName] = true
  currentStep.value = 'preprocess'
  preprocessLogs.value[dataName] = `正在对 ${dataName} 进行预处理...\n`
  
  try {
    const response = await axios.post('/api/preprocess', { inputDir: dataName })
    const jobId = response.data.jobId
    pollPreprocessStatus(jobId, dataName)
  } catch (error) {
    preprocessLogs.value[dataName] += `错误: ${error.message}\n`
    message.value = `预处理失败: ${error.message}`
    messageType.value = 'error'
    processingStatus.value[dataName] = false
  }
}

const pollPreprocessStatus = (jobId, dataName) => {
  const timerId = `preprocess-${dataName}-${Date.now()}`
  
  const interval = setInterval(async () => {
    try {
      const response = await axios.get(`/api/status/${jobId}`)
      if (response.data) {
        if (response.data.log) {
          preprocessLogs.value[dataName] = response.data.log
        }
        
        if (response.data.status === 'COMPLETED' || response.data.status === 'DONE' || response.data.status === 'FAILED') {
          clearInterval(interval)
          delete pollingIntervals.value[timerId]
          processingStatus.value[dataName] = false
          
          if (response.data.status === 'COMPLETED' || response.data.status === 'DONE') {
            message.value = `数据集 ${dataName} 预处理成功`
            messageType.value = 'success'
          } else {
            message.value = `数据集 ${dataName} 预处理失败`
            messageType.value = 'error'
          }
          refreshDatasets()
        }
      }
    } catch (error) {
      clearInterval(interval)
      delete pollingIntervals.value[timerId]
      processingStatus.value[dataName] = false
      message.value = `预处理失败: ${error.message}`
      messageType.value = 'error'
    }
  }, 1000)
  
  pollingIntervals.value[timerId] = interval
}

const handleTestDataset = async (dataName) => {
  processingStatus.value[dataName] = true
  currentStep.value = 'test'
  testLogs.value[dataName] = `正在对 ${dataName} 进行测试...\n测试状态: Running\n正在等待节点创建...\n`
  
  try {
    const response = await axios.post('/api/test', { inputDir: dataName })
    const jobId = response.data.jobId
    pollTestStatus(jobId, dataName)
  } catch (error) {
    testLogs.value[dataName] += `错误: ${error.message}\n`
    processingStatus.value[dataName] = false
  }
}

const pollTestStatus = (jobId, dataName) => {
  const timerId = `test-${dataName}-${Date.now()}`
  
  const interval = setInterval(async () => {
    try {
      const response = await axios.get(`/api/status/${jobId}`)
      if (response.data) {
        if (response.data.log) {
          testLogs.value[dataName] = response.data.log
        }
        
        if (response.data.status === 'COMPLETED' || response.data.status === 'DONE' || response.data.status === 'FAILED') {
          clearInterval(interval)
          delete pollingIntervals.value[timerId]
          processingStatus.value[dataName] = false
          
          if (response.data.status === 'COMPLETED' || response.data.status === 'DONE') {
            message.value = `数据集 ${dataName} 测试成功`
            messageType.value = 'success'
          } else {
            message.value = `数据集 ${dataName} 测试失败`
            messageType.value = 'error'
          }
          refreshDatasets()
        }
      }
    } catch (error) {
      clearInterval(interval)
      delete pollingIntervals.value[timerId]
      processingStatus.value[dataName] = false
    }
  }, 1000)
  
  pollingIntervals.value[timerId] = interval
}

const handleViewTrainLogs = async (dataName) => {
  if (!dataName) return
  closedLogs.value[dataName] = false
  
  try {
    const response = await axios.get(`/api/datasets/${dataName}/train-log`)
    if (response.data.log) {
      trainLogs.value[dataName] = response.data.log
    }
    
    const dataset = datasetList.value.find(d => d.name === dataName)
    const isRunning = dataset && dataset.internalTrainStatus === 'RUNNING'
    if (isRunning) {
      startTrainLogPolling(dataName)
    }
  } catch (error) {
    trainLogs.value[dataName] = `获取日志失败: ${error.message}`
  }
}

const startTrainLogPolling = (dataName) => {
  const timerId = `train-log-${dataName}`
  
  if (pollingIntervals.value[timerId]) {
    clearInterval(pollingIntervals.value[timerId])
  }
  
  const interval = setInterval(async () => {
    try {
      const response = await axios.get(`/api/datasets/${dataName}/train-log`)
      if (response.data.log && closedLogs.value[dataName] !== true) {
        trainLogs.value[dataName] = response.data.log
      }
      
      if (response.data.status === 'COMPLETED' || response.data.status === 'DONE' || response.data.status === 'FAILED') {
        clearInterval(interval)
        delete pollingIntervals.value[timerId]
        await refreshDatasets()
      }
    } catch (error) {
      console.error('获取训练日志失败:', error)
    }
  }, 1000)
  
  pollingIntervals.value[timerId] = interval
}

const handleViewTestLogs = async (dataName) => {
  if (!dataName) return
  closedLogs.value[dataName] = false
  
  try {
    const response = await axios.get(`/api/datasets/${dataName}/test-log`)
    if (response.data.log) {
      testLogs.value[dataName] = response.data.log
    }
    
    const dataset = datasetList.value.find(d => d.name === dataName)
    const isRunning = dataset && dataset.internalTestStatus === 'RUNNING'
    if (isRunning) {
      startTestLogPolling(dataName)
    }
  } catch (error) {
    testLogs.value[dataName] = `获取日志失败: ${error.message}`
  }
}

const startTestLogPolling = (dataName) => {
  const timerId = `test-log-${dataName}`
  
  if (pollingIntervals.value[timerId]) {
    clearInterval(pollingIntervals.value[timerId])
  }
  
  const interval = setInterval(async () => {
    try {
      const resp = await axios.get(`/api/datasets/${dataName}/test-log`)
      if (resp.data.log && closedLogs.value[dataName] !== true) {
        testLogs.value[dataName] = resp.data.log
      }
      
      if (resp.data.status === 'COMPLETED' || resp.data.status === 'DONE' || resp.data.status === 'FAILED') {
        clearInterval(interval)
        delete pollingIntervals.value[timerId]
        await refreshDatasets()
      }
    } catch (error) {
      console.error('获取测试日志失败:', error)
    }
  }, 1000)
  
  pollingIntervals.value[timerId] = interval
}

const handleDeleteDataset = (dataName) => {
  datasetToDelete.value = dataName
  deleteConfirmVisible.value = true
}

const confirmDelete = async () => {
  try {
    await axios.delete(`/api/datasets/${datasetToDelete.value}`)
    await refreshDatasets()
    message.value = '数据集已删除'
    messageType.value = 'success'
  } catch (error) {
    message.value = '删除失败: ' + error.message
    messageType.value = 'error'
  }
  deleteConfirmVisible.value = false
  datasetToDelete.value = ''
}

const cancelDelete = () => {
  deleteConfirmVisible.value = false
  datasetToDelete.value = ''
}

const refreshDatasets = async () => {
  try {
    const response = await axios.get('/api/datasets')
    datasetList.value = response.data
    total.value = response.data.length
    if (currentPage.value > Math.ceil(total.value / pageSize.value)) {
      currentPage.value = 1
    }
  } catch (error) {
    console.error('获取数据集列表失败:', error)
  }
}

const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
}

const handleCurrentChange = (val) => {
  currentPage.value = val
}

const closePreprocessLog = (dataName) => {
  delete preprocessLogs.value[dataName]
  closedLogs.value[dataName] = true
  stopPollingByDataName(dataName, 'preprocess')
}

const closeTrainLog = (dataName) => {
  delete trainLogs.value[dataName]
  closedLogs.value[dataName] = true
  stopPollingByDataName(dataName, 'train')
}

const closeTestLog = (dataName) => {
  delete testLogs.value[dataName]
  closedLogs.value[dataName] = true
  stopPollingByDataName(dataName, 'test')
}

const stopPollingByDataName = (dataName, taskType) => {
  Object.keys(pollingIntervals.value).forEach(timerId => {
    if (timerId.includes(dataName)) {
      clearInterval(pollingIntervals.value[timerId])
      delete pollingIntervals.value[timerId]
    }
  })
}

const showSaveModelDialog = (dataName) => {
  saveModelForm.dataName = dataName
  saveModelForm.modelType = 'best'
  saveModelForm.savePath = ''
  saveModelDialogVisible.value = true
}

const confirmSaveModel = async () => {
  saveModelLoading.value = true
  try {
    const response = await axios.post('/api/model/save', {
      dataName: saveModelForm.dataName,
      modelType: saveModelForm.modelType,
      savePath: saveModelForm.savePath
    })
    
    if (response.data.status === 'success') {
      message.value = '模型保存成功'
      messageType.value = 'success'
      saveModelDialogVisible.value = false
    }
  } catch (error) {
    const errorMsg = error.response?.data?.message || '保存模型失败'
    message.value = errorMsg
    messageType.value = 'error'
  } finally {
    saveModelLoading.value = false
  }
}

const handlePriorityChange = async (dataName, priority) => {
  try {
    await axios.post('/api/scheduler/priority', { dataName, priority })
    message.value = `优先级已更新为 ${priority}`
    messageType.value = 'success'
  } catch (error) {
    message.value = '更新优先级失败: ' + error.message
    messageType.value = 'error'
    refreshDatasets()
  }
}

const handleScheduleTrain = async (dataName) => {
  try {
    closedLogs.value[dataName] = false
    trainLogs.value[dataName] = `正在将 ${dataName} 加入训练队列...\n训练状态: Queued\n等待中...\n`

    const response = await axios.post('/api/scheduler/add', { dataName })
    message.value = response.data.message
    messageType.value = 'success'
    
    startQueueMonitoring(dataName)
    refreshDatasets()
  } catch (error) {
    message.value = error.response?.data?.message || '添加到队列失败'
    messageType.value = 'error'
  }
}

const startQueueMonitoring = (dataName) => {
  const timerId = `queue-${dataName}-${Date.now()}`
  let logPollingStarted = false
  
  const interval = setInterval(async () => {
    try {
      if (closedLogs.value[dataName] === true) {
        clearInterval(interval)
        delete pollingIntervals.value[timerId]
        return
      }
      
      const queueResponse = await axios.get('/api/scheduler/queue')
      const currentTask = queueResponse.data.find(t => t.dataName === dataName)
      
      if (currentTask) {
        if (currentTask.status === 'RUNNING' && !logPollingStarted) {
          logPollingStarted = true
          startTrainLogPolling(dataName)
        } else if (currentTask.status === 'COMPLETED' || currentTask.status === 'FAILED') {
          clearInterval(interval)
          delete pollingIntervals.value[timerId]
          await refreshDatasets()
        }
      } else {
        const dataset = datasetList.value.find(d => d.name === dataName)
        if (dataset && (dataset.internalTrainStatus === 'COMPLETED' || dataset.internalTrainStatus === 'FAILED')) {
          clearInterval(interval)
          delete pollingIntervals.value[timerId]
        }
      }
      
      await refreshDatasets()
    } catch (error) {
      console.error('队列监控失败:', error)
    }
  }, 2000)
  
  pollingIntervals.value[timerId] = interval
}

const handleMaxConcurrentTasksChange = async (val) => {
  try {
    await axios.post('/api/scheduler/max-concurrent', { max: val })
    const savedValue = await axios.get('/api/scheduler/max-concurrent')
    savedMaxConcurrentTasks.value = savedValue.data.max
    message.value = `最大同时训练数已设置为 ${savedMaxConcurrentTasks.value}`
    messageType.value = 'success'
  } catch (error) {
    message.value = '更新并发数失败'
    messageType.value = 'error'
  }
}

const getStatusClass = (status, fallbackFlag) => {
  if (status === 'RUNNING') return 'warning'
  if (status === 'QUEUED') return 'primary'
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (fallbackFlag) return 'success'
  return 'info'
}

const getStatusText = (status, type, fallbackFlag) => {
  const prefix = type === 'train' ? '训练' : '测试'
  if (status === 'RUNNING') return `${prefix}中`
  if (status === 'QUEUED') return '排队中'
  if (status === 'COMPLETED') return '已完成'
  if (status === 'FAILED') return '失败'
  if (fallbackFlag) return '已完成'
  return type === 'train' ? '未训练' : '未测试'
}

onMounted(async () => {
  refreshDatasets()
  
  try {
    const response = await axios.get('/api/scheduler/max-concurrent')
    if (response.data && response.data.max) {
      maxConcurrentTasks.value = response.data.max
      savedMaxConcurrentTasks.value = response.data.max
    }
  } catch (error) {
    console.error('获取并发数配置失败:', error)
  }
})
</script>

<style scoped>
.app-container {
  min-height: 100vh;
  background-color: #f5f5f5;
  padding: 20px;
}

.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  align-items: center;
}

.flex-item {
  flex: 1;
  min-width: 280px;
  margin-bottom: 0;
}

.status-indicator {
  margin-left: 15px;
}

.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.app-header h1 {
  margin: 0;
  font-size: 24px;
  color: #303133;
}

.datasets-section {
  margin-top: 20px;
}

.pagination-container {
  display: flex;
  justify-content: center;
  margin-top: 20px;
  padding-top: 10px;
  border-top: 1px solid #ebeef5;
}

.operation-item {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.status-tag {
  margin-right: 10px;
  flex-shrink: 0;
}

.add-dataset-section .el-card__body {
  padding: 20px;
}

.input-container {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}

.input-container .el-input {
  flex: 1;
}

.input-container .el-button {
  min-width: 100px;
}

:deep(.el-table .el-table__header-wrapper th) {
  text-align: center !important;
}

.log-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}

.logs-section {
  margin-top: 20px;
}

.log-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
}

.log-item {
  flex: 0 0 calc(50% - 10px);
  max-width: calc(50% - 10px);
  margin-bottom: 15px;
}

.log-card {
  height: 100%;
}

.log-container {
  max-height: 300px;
  overflow-y: auto;
}

.log-content {
  white-space: pre-wrap;
  font-family: 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 13px;
  color: #303133;
  margin: 0;
  line-height: 1.5;
}

.priority-container {
  display: flex;
  align-items: center;
  gap: 5px;
}

@media (max-width: 1200px) {
  .operation-item {
    flex-direction: column;
    align-items: flex-start;
  }
  
  .status-tag {
    margin-bottom: 5px;
  }
  
  .log-item {
    flex: 0 0 100%;
    max-width: 100%;
  }
}

@media (max-width: 900px) {
  .form-row {
    flex-direction: column;
    align-items: stretch;
    gap: 15px;
  }
  
  .flex-item {
    min-width: auto;
  }
  
  .input-container {
    flex-wrap: wrap;
  }
  
  .input-container .el-input {
    min-width: 200px;
  }
  
  .status-indicator {
    margin-left: 0;
    margin-top: 10px;
    width: 100%;
  }
}

@media (max-width: 600px) {
  .app-container {
    padding: 10px;
  }
  
  .app-header h1 {
    font-size: 18px;
  }
  
  .input-container .el-button {
    min-width: 80px;
  }
}
</style>
