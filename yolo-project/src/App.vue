<template>
  <div class="app-container">
    <div class="bg-decoration">
      <div class="bg-circle bg-circle-1"></div>
      <div class="bg-circle bg-circle-2"></div>
      <div class="bg-circle bg-circle-3"></div>
    </div>

    <div class="content-wrapper">
      <header class="app-header">
        <div class="header-content">
          <div class="logo-area">
            <div class="logo-icon">Y</div>
            <h1>YOLO 训练管理系统</h1>
          </div>
        </div>
      </header>

      <main class="app-main">
        <el-alert
          v-if="message"
          :title="message"
          :type="messageType"
          show-icon
          :closable="true"
          @close="message = ''"
          style="margin-bottom: 16px"
        />

        <div class="top-section">
          <el-card shadow="hover" class="dataset-input-card">
            <template #header>
              <span class="card-title">📁 添加数据集</span>
            </template>
            <div class="input-col">
              <el-input
                v-model="form.inputDir"
                placeholder="请输入数据路径，如：E:\Ancious\Desktop\data1"
                @keyup.enter="handleAddDataset"
                size="large"
                clearable
              />
              <div class="input-actions">
                <el-button type="info" @click="browseFolder('请选择数据集文件夹', 'input')">浏览</el-button>
                <el-button type="primary" @click="handleAddDataset" :loading="currentStep === 'addDataset'">添加数据集</el-button>
              </div>
            </div>
          </el-card>

          <el-card shadow="hover" class="config-card">
            <template #header>
              <span class="card-title">⚙️ 全局配置</span>
            </template>
            <div class="config-grid">
              <div class="config-item">
                <span class="config-label">同时训练数</span>
                <el-input-number v-model="maxConcurrentTasks" :min="1" :max="10" size="default" style="width: 120px" />
                <el-button type="primary" size="small" @click="handleMaxConcurrentTasksChange(maxConcurrentTasks)">保存</el-button>
                <span class="config-current">当前: {{ savedMaxConcurrentTasks }}</span>
              </div>
              <div class="config-item">
                <span class="config-label">Epochs</span>
                <el-input-number v-model="defaultEpochs" :min="1" :max="1000" size="default" style="width: 120px" />
                <el-button type="primary" size="small" @click="handleDefaultEpochsChange(defaultEpochs)">保存</el-button>
                <span class="config-current">当前: {{ savedDefaultEpochs }}</span>
              </div>
              <div class="config-item">
                <span class="config-label">Imgsz</span>
                <el-input-number v-model="defaultImgsz" :min="32" :max="1280" :step="32" size="default" style="width: 120px" />
                <el-button type="primary" size="small" @click="handleDefaultImgszChange(defaultImgsz)">保存</el-button>
                <span class="config-current">当前: {{ savedDefaultImgsz }}</span>
              </div>
            </div>
          </el-card>
        </div>

        <el-dialog
          title="训练参数配置"
          v-model="trainDialogVisible"
          width="420px"
          :close-on-click-modal="false"
        >
          <el-form :model="trainForm" label-width="80px">
            <el-form-item label="数据集">
              <el-tag type="primary" size="large">{{ trainForm.dataName }}</el-tag>
            </el-form-item>
            <el-form-item label="Epochs">
              <el-input-number v-model="trainForm.epochs" :min="1" :max="1000" style="width: 180px" />
              <span class="hint-text">默认: {{ savedDefaultEpochs }}</span>
            </el-form-item>
            <el-form-item label="Imgsz">
              <el-input-number v-model="trainForm.imgsz" :min="32" :max="1280" :step="32" style="width: 180px" />
              <span class="hint-text">默认: {{ savedDefaultImgsz }}</span>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="trainDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="confirmTrain">开始训练</el-button>
          </template>
        </el-dialog>

        <el-dialog
          title="保存模型"
          v-model="saveModelDialogVisible"
          width="480px"
          :close-on-click-modal="false"
        >
          <el-form :model="saveModelForm" label-width="80px">
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
              <div class="save-path-row">
                <el-input v-model="saveModelForm.savePath" placeholder="默认保存到项目 saved_models 目录" />
                <el-button type="info" @click="browseFolder('请选择模型保存路径', 'save')">浏览</el-button>
                <el-button @click="saveModelForm.savePath = 'E:\\Ancious\\Desktop\\毕业设计\\Code\\saved_models'">默认</el-button>
              </div>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button type="primary" @click="confirmSaveModel" :loading="saveModelLoading">保存模型</el-button>
          </template>
        </el-dialog>

        <div class="datasets-section">
          <el-card shadow="hover">
            <template #header>
              <span class="card-title">📊 数据集管理</span>
            </template>
            <div class="table-wrapper">
              <el-table :data="pagedDatasets" border stripe>
                <el-table-column prop="name" label="数据集" width="140" align="center" />

                <el-table-column label="预处理" width="160" align="center">
                  <template #default="scope">
                    <div class="action-group">
                      <el-tag :type="scope.row.preprocessed ? 'success' : 'info'" size="small">
                        {{ scope.row.preprocessed ? '已完成' : '未处理' }}
                      </el-tag>
                      <el-button
                        v-if="!scope.row.preprocessed"
                        size="small"
                        type="warning"
                        :loading="processingStatus[scope.row.name]"
                        @click="handlePreprocessDataset(scope.row.name)"
                      >
                        预处理
                      </el-button>
                    </div>
                  </template>
                </el-table-column>

                <el-table-column label="优先级" width="100" align="center">
                  <template #default="scope">
                    <el-input-number
                      v-model="scope.row.priority"
                      :min="1"
                      :max="10"
                      size="small"
                      style="width: 72px"
                      :disabled="scope.row.internalTrainStatus === 'RUNNING'"
                      @change="handlePriorityChange(scope.row.name, $event)"
                    />
                  </template>
                </el-table-column>

                <el-table-column label="训练进度" width="180" align="center">
                  <template #default="scope">
                    <el-progress
                      v-if="scope.row.trainProgress > 0"
                      :percentage="scope.row.trainProgress"
                      :stroke-width="20"
                      :text-inside="true"
                      style="width: 100%"
                    />
                    <span v-else style="color: #c0c4cc">-</span>
                  </template>
                </el-table-column>

                <el-table-column label="训练" min-width="260" align="center">
                  <template #default="scope">
                    <div class="action-group">
                      <el-tag :type="getStatusClass(scope.row.internalTrainStatus, scope.row.trained)" size="small">
                        {{ getStatusText(scope.row.internalTrainStatus, 'train', scope.row.trained) }}
                      </el-tag>
                      <el-button
                        size="small"
                        type="primary"
                        :disabled="!scope.row.preprocessed || scope.row.internalTrainStatus === 'RUNNING' || scope.row.internalTrainStatus === 'QUEUED'"
                        @click="showTrainDialog(scope.row.name)"
                      >
                        训练
                      </el-button>
                      <el-button
                        size="small"
                        :disabled="scope.row.internalTrainStatus === 'IDLE' || !scope.row.internalTrainStatus"
                        @click="handleViewTrainLogs(scope.row.name)"
                      >
                        日志
                      </el-button>
                      <el-button
                        size="small"
                        type="success"
                        :disabled="!scope.row.trained"
                        @click="showSaveModelDialog(scope.row.name)"
                      >
                        保存
                      </el-button>
                    </div>
                  </template>
                </el-table-column>

                <el-table-column label="测试" min-width="180" align="center">
                  <template #default="scope">
                    <div class="action-group">
                      <el-tag :type="getStatusClass(scope.row.internalTestStatus, scope.row.tested)" size="small">
                        {{ getStatusText(scope.row.internalTestStatus, 'test', scope.row.tested) }}
                      </el-tag>
                      <el-button
                        size="small"
                        type="success"
                        :disabled="!scope.row.trained || processingStatus[scope.row.name]"
                        @click="handleTestDataset(scope.row.name)"
                      >
                        测试
                      </el-button>
                      <el-button
                        size="small"
                        :disabled="scope.row.internalTestStatus === 'IDLE' || !scope.row.internalTestStatus"
                        @click="handleViewTestLogs(scope.row.name)"
                      >
                        日志
                      </el-button>
                    </div>
                  </template>
                </el-table-column>

                <el-table-column label="操作" width="90" align="center" fixed="right">
                  <template #default="scope">
                    <el-button size="small" type="danger" @click="handleDeleteDataset(scope.row.name)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="pagination-container">
              <el-pagination
                v-model:current-page="currentPage"
                :page-size="pageSize"
                :total="total"
                :page-sizes="[5, 10, 20, 50]"
                layout="total, sizes, prev, pager, next"
                @size-change="handleSizeChange"
                @current-change="handleCurrentChange"
                small
              />
            </div>
          </el-card>
        </div>

        <div class="logs-section" v-if="Object.keys(preprocessLogs).length > 0">
          <el-card shadow="hover">
            <template #header>
              <span class="card-title">📝 预处理日志</span>
            </template>
            <div class="log-grid">
              <div v-for="(log, dataName) in preprocessLogs" :key="dataName" class="log-item">
                <div class="log-header">
                  <el-tag size="small">{{ dataName }}</el-tag>
                  <el-button type="danger" size="small" link @click="closePreprocessLog(dataName)">关闭</el-button>
                </div>
                <div class="log-container" :ref="el => setLogRef(dataName, 'preprocess', el)">
                  <pre class="log-content">{{ log }}</pre>
                </div>
              </div>
            </div>
          </el-card>
        </div>

        <div class="logs-section" v-if="Object.keys(trainLogs).length > 0">
          <el-card shadow="hover">
            <template #header>
              <span class="card-title">🔥 训练日志</span>
            </template>
            <div class="log-grid">
              <div v-for="(log, dataName) in trainLogs" :key="dataName" class="log-item">
                <div class="log-header">
                  <el-tag size="small" type="warning">{{ dataName }}</el-tag>
                  <el-button type="danger" size="small" link @click="closeTrainLog(dataName)">关闭</el-button>
                </div>
                <div class="log-container" :ref="el => setLogRef(dataName, 'train', el)">
                  <pre class="log-content">{{ log }}</pre>
                </div>
              </div>
            </div>
          </el-card>
        </div>

        <div class="logs-section" v-if="Object.keys(testLogs).length > 0">
          <el-card shadow="hover">
            <template #header>
              <span class="card-title">✅ 测试日志</span>
            </template>
            <div class="log-grid">
              <div v-for="(log, dataName) in testLogs" :key="dataName" class="log-item">
                <div class="log-header">
                  <el-tag size="small" type="success">{{ dataName }}</el-tag>
                  <el-button type="danger" size="small" link @click="closeTestLog(dataName)">关闭</el-button>
                </div>
                <div class="log-container" :ref="el => setLogRef(dataName, 'test', el)">
                  <pre class="log-content">{{ log }}</pre>
                </div>
              </div>
            </div>
          </el-card>
        </div>

        <el-dialog
          title="确认删除"
          v-model="deleteConfirmVisible"
          width="360px"
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
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, nextTick, computed, watch } from 'vue'
import axios from 'axios'

const form = reactive({ inputDir: '' })

const maxConcurrentTasks = ref(2)
const savedMaxConcurrentTasks = ref(2)
const defaultEpochs = ref(2)
const savedDefaultEpochs = ref(2)
const defaultImgsz = ref(640)
const savedDefaultImgsz = ref(640)
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

const trainDialogVisible = ref(false)
const trainForm = reactive({
  dataName: '',
  epochs: 2,
  imgsz: 640
})

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
    const response = await axios.post('/api/preprocess', { 
      inputDir: dataName,
      epochs: savedDefaultEpochs.value,
      imgsz: savedDefaultImgsz.value
    })
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
    const response = await axios.post('/api/test', { 
      inputDir: dataName,
      imgsz: savedDefaultImgsz.value
    })
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

const showTrainDialog = (dataName) => {
  trainForm.dataName = dataName
  trainForm.epochs = savedDefaultEpochs.value
  trainForm.imgsz = savedDefaultImgsz.value
  trainDialogVisible.value = true
}

const confirmTrain = async () => {
  const dataName = trainForm.dataName
  trainDialogVisible.value = false
  
  try {
    closedLogs.value[dataName] = false
    trainLogs.value[dataName] = `正在将 ${dataName} 加入训练队列...\n训练参数: epochs=${trainForm.epochs}, imgsz=${trainForm.imgsz}\n训练状态: Queued\n等待中...\n`

    const response = await axios.post('/api/scheduler/add', { 
      dataName, 
      epochs: trainForm.epochs, 
      imgsz: trainForm.imgsz 
    })
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

const handleDefaultEpochsChange = async (val) => {
  try {
    await axios.post('/api/scheduler/default-epochs', { epochs: val })
    savedDefaultEpochs.value = val
    message.value = `默认训练轮数已设置为 ${val}`
    messageType.value = 'success'
  } catch (error) {
    message.value = '更新训练轮数失败'
    messageType.value = 'error'
  }
}

const handleDefaultImgszChange = async (val) => {
  try {
    await axios.post('/api/scheduler/default-imgsz', { imgsz: val })
    savedDefaultImgsz.value = val
    message.value = `默认图像尺寸已设置为 ${val}`
    messageType.value = 'success'
  } catch (error) {
    message.value = '更新图像尺寸失败'
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
    const response = await axios.get('/api/scheduler/config')
    if (response.data) {
      maxConcurrentTasks.value = response.data.maxConcurrentTasks
      savedMaxConcurrentTasks.value = response.data.maxConcurrentTasks
      defaultEpochs.value = response.data.defaultEpochs
      savedDefaultEpochs.value = response.data.defaultEpochs
      defaultImgsz.value = response.data.defaultImgsz
      savedDefaultImgsz.value = response.data.defaultImgsz
    }
  } catch (error) {
    console.error('获取配置失败:', error)
  }
})
</script>

<style scoped>
.app-container {
  min-height: 100vh;
  background: linear-gradient(135deg, #0f0c29 0%, #302b63 50%, #24243e 100%);
  position: relative;
  overflow-x: hidden;
}

.bg-decoration {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
}

.bg-circle {
  position: absolute;
  border-radius: 50%;
  opacity: 0.08;
}

.bg-circle-1 {
  width: 600px;
  height: 600px;
  background: #409eff;
  top: -200px;
  right: -100px;
}

.bg-circle-2 {
  width: 400px;
  height: 400px;
  background: #67c23a;
  bottom: -100px;
  left: -80px;
}

.bg-circle-3 {
  width: 300px;
  height: 300px;
  background: #e6a23c;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

.content-wrapper {
  position: relative;
  z-index: 1;
  padding: 24px;
  max-width: 1600px;
  margin: 0 auto;
}

.app-header {
  margin-bottom: 24px;
}

.header-content {
  display: flex;
  align-items: center;
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 14px;
}

.logo-icon {
  width: 42px;
  height: 42px;
  background: linear-gradient(135deg, #409eff, #67c23a);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 22px;
  font-weight: 800;
  flex-shrink: 0;
}

.app-header h1 {
  margin: 0;
  font-size: 22px;
  color: #fff;
  font-weight: 700;
  letter-spacing: 1px;
}

.top-section {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
}

.dataset-input-card {
  flex: 1;
  min-width: 0;
}

.config-card {
  width: 460px;
  flex-shrink: 0;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.input-col {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.input-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}

.config-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.config-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-label {
  width: 80px;
  font-size: 13px;
  color: #606266;
  flex-shrink: 0;
  text-align: right;
}

.config-current {
  font-size: 12px;
  color: #909399;
  flex-shrink: 0;
}

.datasets-section {
  margin-bottom: 16px;
}

.table-wrapper {
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.pagination-container {
  display: flex;
  justify-content: center;
  margin-top: 16px;
  padding-top: 12px;
}

.action-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-wrap: nowrap;
  white-space: nowrap;
}

.hint-text {
  margin-left: 8px;
  color: #909399;
  font-size: 12px;
}

.save-path-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.save-path-row .el-input {
  flex: 1;
}

:deep(.el-table .el-table__header-wrapper th) {
  text-align: center !important;
}

:deep(.el-table .el-table__body-wrapper td) {
  vertical-align: middle !important;
}

:deep(.el-card) {
  border-radius: 12px;
  border: none;
}

:deep(.el-card__header) {
  padding: 14px 20px;
  border-bottom: 1px solid #ebeef5;
}

:deep(.el-card__body) {
  padding: 16px 20px;
}

:deep(.el-table) {
  font-size: 13px;
}

:deep(.el-table th.el-table__cell) {
  background-color: #f5f7fa !important;
}

.logs-section {
  margin-bottom: 16px;
}

.log-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(480px, 1fr));
  gap: 16px;
}

.log-item {
  border: 1px solid #30363d;
  border-radius: 8px;
  overflow: hidden;
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 14px;
  background: #161b22;
  border-bottom: 1px solid #30363d;
}

.log-container {
  max-height: 350px;
  overflow-y: auto;
  padding: 10px 14px;
  background: #0d1117;
}

.log-content {
  white-space: pre-wrap;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  color: #c9d1d9;
  margin: 0;
  line-height: 1.6;
}

:deep(.el-alert) {
  border-radius: 10px;
}

:deep(.el-dialog) {
  border-radius: 12px;
}

@media (max-width: 1100px) {
  .top-section {
    flex-direction: column;
  }
  
  .config-card {
    width: 100%;
  }
  
  .config-grid {
    flex-direction: row;
    flex-wrap: wrap;
    gap: 12px;
  }
  
  .config-item {
    flex: 1;
    min-width: 200px;
  }
}

@media (max-width: 800px) {
  .content-wrapper {
    padding: 12px;
  }
  
  .app-header h1 {
    font-size: 18px;
  }
  
  .logo-icon {
    width: 36px;
    height: 36px;
    font-size: 18px;
  }
  
  .log-grid {
    grid-template-columns: 1fr;
  }
}
</style>
